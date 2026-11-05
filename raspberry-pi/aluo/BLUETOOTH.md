# 2026-10-31 AirPods Max 连接

用户要求将树莓派连接其AirPods Max。目标为已配对的“泓博的AirPods Max”。

完成：解除树莓派蓝牙rfkill软屏蔽，开启蓝牙；安装pipewire-audio及依赖，启动用户pipewire、pipewire-pulse、wireplumber服务；配对并信任目标耳机，连接成功；将耳机设为PipeWire默认输出。

无桌面配置（树莓派用户目录）：`~/.config/wireplumber/wireplumber.conf.d/51-aluo-headless-bluetooth.conf`：

```ini
wireplumber.profiles = {
  main = {
    monitor.bluez.seat-monitoring = disabled
  }
}
```

检查：BlueZ Paired/Trusted/Connected均为yes；PipeWire输出节点`bluez_output.00_61_AB_00_38_15.1`，A2DP/SBC。未播放测试音、未执行音量设置命令；麦克风与真实听感未验证。原阿洛程序仍默认指定3.5mm声卡，后续需通过`--output`指定PipeWire输出，不应假定系统默认切换会覆盖程序硬编码的Headphones设备。

配置依据：https://pipewire.pages.freedesktop.org/wireplumber/daemon/configuration/bluetooth.html 。蓝牙配对参考：https://support.apple.com/en-gb/guide/airpods/dev499c9718b/web 。

`aplay -L`确认存在`pipewire`及默认PipeWire ALSA设备，默认输出节点复核为AirPods Max。后续阿洛播报可使用`--speak --output pipewire`；此命令本次未执行。实时麦克风仍需单独配置和验证。

已启用该用户linger，复核Linger=yes，三个用户音频服务均active，使SSH退出后用户音频服务可继续运行。重启后重连尚未验证。

## 2026-10-31 实际输入输出验证

- AirPods Max播放本地espeak-ng中文提示，pw-play退出0；用户确认“听清了”。没有执行音量设置命令；测试提示使用合成幅度40。
- A2DP阶段公开输入节点录音4.8835625秒，全零。切mSBC后直接针对内部节点录音7.8701875秒，仍全零，节点音量1.00且未静音。静音直接ASR输出“我的我…”属于幻觉，不计通过。
- 切换CVSD（设备77的profile index 196864），通过公开输入`bluez_input.00:61:AB:00:38:15`录制PCM16/16kHz/mono，7.8701875秒，峰值20855，RMS2128.604。timeout返回124表示按时结束，不代表录音设备失败。
- 本地Paraformer识别结果原样为“你好阿洛我正在测试麦克风你好阿”，解码2.086秒。包含尾部截断/重复，不声称全文逐字准确。
- 切回A2DP回放该录音，用户确认“能清楚听到”。原音只在树莓派`/tmp/aluo-airpods-mic-cvsd.wav`，未下载到Mac或上传公网。
- 验证结束恢复已验证的CVSD通话配置，供后续双向语音使用。CVSD语音带宽低于A2DP音乐模式。
- mSBC与CVSD测试使用了不同的目标节点，因此不能据此认定mSBC编码器存在兼容性故障；尚未做同条件对照。
- 结论：本轮AirPods Max人声输入与耳机输出通过，尚未验证阿洛实时唤醒、确认、真实天气闭环或长时间稳定性。现有aluo.py实时录音仍默认USB ALSA设备，不应将本次PipeWire诊断通过视为程序已完成蓝牙采集适配。
