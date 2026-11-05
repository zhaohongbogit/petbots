# 阿洛：树莓派 Linux 无界面版

迁移自 `i.MX7D/imx7d-pet-screen` 的 VAD + Paraformer 短句方案。
当前范围：完整短句“你好阿洛”唤醒、城市今天/明天天气、明确口头确认、取消、超时、60秒重复查询冷却。未迁移旅行订单、表情界面或开机自启。

## 安装

目标 Raspberry Pi OS Lite 64位，Python3。安装 `python3-venv espeak-ng alsa-utils`。

```sh
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

从原项目 `build/sherpa-models` 复制 `asr/` 和 `vad/` 到本目录 `models/`。程序启动时核对 `models.json` 的大小与SHA-256。模型及依赖许可沿用原项目来源；模型不提交Git。

## 运行

```sh
# 查看输入设备（树莓派3.5mm口不提供麦克风输入）
arecord -l
# 文字模式：不录音、不播放；只有确认后才查询真实天气
.venv/bin/python aluo.py --text
# 离线识别：仅16kHz/单声道/PCM16 WAV，不执行业务或播放
.venv/bin/python aluo.py --wav /path/to/test.wav
# 真人语音：将Device替换为arecord -l对应声卡名
.venv/bin/python aluo.py --device plughw:CARD=Device,DEV=0 --speak
```

默认不播放；`--speak` 明确启用离线Piper中文神经语音及3.5mm耳机输出，蓝牙输出使用`--airpods`。Piper模型准备见下文；`--tts espeak`仅用于显式回退。采集进程在识别和播报前关闭，不上传或保存原始音频。仅输出识别文本及状态日志。

先独立说“你好阿洛”，等“收到”播完，再说“北京明天的天气怎么样”，确认提示播完后说“是的”。必须明确城市，不默认猜测；确认等待8秒。当前无持续流式采集，切换/识别时存在输入空窗，需按提示说话。

天气使用与Android相同的Open-Meteo接口（仅发送城市坐标），确认后调用。测试模拟查询不代表后端接口验证。旅行服务及订单操作尚未接入。

## 验证

```sh
python3 -m unittest discover -s tests
python3 -m py_compile aluo.py
```

2026-10-31：主机6项状态机测试通过，Python语法检查通过。树莓派无录音设备，真人识别、唤醒、天气完整闭环未验证。部署及依赖检查进展见 `VALIDATION.md`。

文件回放验证：Mac 上运行 `sh tests/generate_samples.sh`（需要本机中文Tingting声音、ffmpeg；只写文件不播放），把生成的 `samples/` 复制到树莓派后运行 `.venv/bin/python tests/replay.py`。脚本只模拟天气查询，报告原始识别结果；当前合成唤醒被识别为“你好阿罗”，因此没有通过唤醒，不能把天气/确认识别通过等同于全流程通过。

## AirPods Max 实时语音

确保蓝牙已配对且使用通过录音验证的CVSD通话模式，再运行：

```sh
.venv/bin/python -u aluo.py --airpods --speak
```

此模式显式使用`pw-record --raw`从AirPods公开输入节点读取16kHz单声道PCM；停止采集后识别、合成并通过`pw-play`向同一耳机播放。输入与输出节点名按本机已配对的耳机配置；该选项不适用于其他蓝牙耳机。临时TTS WAV播放后删除，原始麦克风音频不持久化。不会自行配对、切换配置或设置音量。每轮首次收到音频块时输出`input_ready`日志。

## 自然中文声音（当前默认）

当前默认Piper 1.8.0 + huayan medium中文神经语音，用户已试听确认比eSpeak自然。模型常驻内存；`--tts espeak`仅为显式回退。安装依赖后，将`zh_CN-huayan-medium.onnx`与同名`.onnx.json`放到`tts/`，并把仓库`tts-models.json`复制为`tts/manifest.json`，运行前核对大小和SHA-256。

模型来源：rhasspy/piper-voices的`zh/zh_CN/huayan/medium`（本次通过hf-mirror.com获取），文件模型卡见`licenses/huayan-MODEL_CARD.txt`。Piper运行库GPL-3.0-or-later；模型卡的数据集许可标为Unknown，本次仅本地使用，未发布模型。模型与运行库是独立许可，不能把运行库许可当作声音模型许可。

新声音试听采用A2DP高质量播放；实时双向对话恢复已验证的CVSD通话模式，带宽会影响听感。没有进行声音克隆。AirPods模式按短语分段合成并通过有界队列重叠播放，缓存“收到”；其他ALSA输出仍为完整文件播放。首次加载约18.7秒，之后复用。短语切分可能影响连贯性，需真人验收。

## 开机启动与耳机重连

将`systemd/aluo.service`安装到`~/.config/systemd/user/aluo.service`，启用用户linger（`sudo loginctl enable-linger zhaohongbo`），并执行：

```sh
systemctl --user daemon-reload
systemctl --user enable --now aluo.service
systemctl --user status aluo.service
# 暂停监听，保持开机启动配置
systemctl --user stop aluo.service
# 取消开机启动并立即停止
systemctl --user disable --now aluo.service
```

自启动守护程序`autostart.py`每5秒检查音频图。耳机未就绪时不启动语音采集，每15秒尝试连接已配对的AirPods Max；连接后动态读取声卡ID与CVSD配置编号，选择已验证的通话配置，再启动阿洛。耳机断开或音频图不可用时停止语音进程，恢复后重新加载。若耳机在保护套中休眠、没电、超出范围或被其他设备占用，无法保证立即连接，服务会继续等待。不会重新配对其他耳机，不调整音量。

不要再手动启动第二个aluo.py进程，以免同时占用麦克风。日志通过`systemctl --user status -l aluo.service`或`journalctl --user-unit=aluo.service`查看；原始录音不保存。

## 智能小车（硬件待确认）

已增加动作语音指令、L298N A通道PWM驱动接口、前轮及超声波云台舵机接口、前向超声波停车保护及三种内置舞蹈。接线和标定未确认，GPIO默认关闭；具体动作、配置、模拟方式及未验证项见[CAR.md](CAR.md)。不能把模拟测试通过当作小车运动验证。
