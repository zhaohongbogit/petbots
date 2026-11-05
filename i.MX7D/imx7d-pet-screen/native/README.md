# i.MX7D 本地语音引擎

当前运行链路为 **44.1kHz双声道采集 → 应用内16kHz重采样 → Silero VAD → 一份 Paraformer Small int8 → 完整短句唤醒/指令/确认 → 系统中文离线TTS**。独立3M KWS在真人测试中仍漏检，已经从产品创建路径和模型打包清单移除；历史对比工具保留。已部署的旧KWS文件保留，不需要也不会自动删除。

## 唤醒与交互

口令为“你好阿洛”，应单独说，听到“收到”后再说指令。待机只将不超过2.5秒的VAD语音段交给识别器，长段跳过，避免持续自由听写占满Cortex-A7。指令和确认复用同一识别模型；播报期间暂停采音。文字整句转换后显示，没有逐字partial。

`WakeUtterance` 去除空白与标点后，仅完整匹配“你好阿洛 / 你好啊洛 / 你好阿路 / 你好啊路”四种已观察的读音对应写法；不匹配子串、引用句、带天气内容的长句或近音“你好阿诺/你好阿波”。ASR原文照常显示和记录，不替换为口令文字。该判定不依赖partial，也没有个体声纹模板。

录音、近音样本和现场真人分别验收；录音用于筛选后不能作为独立准确率评测。当前完整结果见 [验证报告](../reports/2026-10-30-sherpa/README.md)，不能把构建或回放完成当作真人闭环通过。

## 采集与资源

使用H340已验证的44.1kHz双声道格式、100ms/8820交错采样读取块、至少2秒AudioRecord缓冲与64块有界队列。JNI内平均双声道，再用sherpa LinearResampler默认低通参数转为16kHz。VAD固定512采样窗口，threshold=0.5、最短语音0.15秒、唤醒尾静音0.4秒、指令/确认尾静音0.7秒、最长段12秒，保留300ms句首预录。短句识别时保持采集；确认和指令在识别前停止采集。溢出报错重启，不拼接断流。

产品只创建一个双线程离线识别模型；同一VAD权重分别创建唤醒和对话状态，以保留指令中的自然停顿。待机自然句末重置VAD；长静音定期重置计数，避免计数长期增长。长期误唤醒/漏唤醒、多说话人、噪声与全天运行尚需单独验证。

## 构建

从本目录执行：

```sh
python3 native/fetch_models.py
bash build.sh
bash build.sh --reuse-model
bash tests/device/build.sh
```

完整包包含三个校验固定的模型文件；更新包只含清单，要求设备已有相同模型。`ALUO_MODEL_ROOT` 可指定本机已准备的目录。默认本机路径为 `build/sherpa-models/`，设备为应用专属 `getExternalFilesDir("speech-models")` 下的 asr/、vad/。冷启动检查尺寸和SHA-256。模型、录音和个人配置不入库。

依赖已有sherpa-onnx 1.13.8 AAR、对应官方C API头文件，使用Android NDK28.2/API27/armeabi-v7a。库在APK中未压缩且按4KiB页面对齐，`extractNativeLibs=false`直接映射，没有修改系统存储阈值。旧BoardSpeech留作历史Vosk对照，新包不打包其原生库。

## 模型与依据

- [Paraformer Small官方说明](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-paraformer/paraformer-models.html#csukuangfj-sherpa-onnx-paraformer-zh-small-2024-03-09-chinese-english)
- [Silero VAD固定下载地址](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx)
- [Android原生库直接加载规范](https://android.googlesource.com/platform/bionic/+/HEAD/android-changes-for-ndk-developers.md)
- [历史KWS候选官方说明](https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html)

`sherpa-models.json` 固定当前活动模型，`model-comparison-manifest.json` 保存候选版本。运行库Apache/MIT许可证随APK打包。

## 验证工具

原生compare工具只读本地WAV，不打开麦克风、TTS或执行业务。历史KWS工具需要另行准备其对比模型，当前产品下载脚本不再下载它们。

`SherpaReplayTest` 通过产品JNI复放立体声真人录音，验证短句判定、天气句、确认句和近音负样本。`SherpaLiveInputTest` 使用真实麦克风和板端TTS，明确跳过唤醒且禁止网络。`VoskModelRetirementTest` 默认只读，只有用户明确批准后才通过专用instrumentation参数移除旧模型。产品没有这些诊断Intent或配置开关。

`tests/playback/AluoWavPlayer.java` 是独立板端PCM回放工具，走媒体流，不自行调整音量；聊天里的本机播放器不能证明板端输出通过。
