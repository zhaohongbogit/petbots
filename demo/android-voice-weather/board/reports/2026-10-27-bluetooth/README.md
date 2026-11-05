# AirPods Pro 实际播放与录音尝试

2026-10-27，Asia/Shanghai；i.MX7D Android Things 1.0.15 / Android 8.1。

结论：AirPods Pro 媒体播放通过（含用户听感确认）；蓝牙耳机录音未能启动，当前板端 HFP/SCO 通道不可用，不能据此认定耳机硬件损坏。

## 播放

- 新增 board 仪器测试 `BluetoothPlaybackTest`，查找 AirPods A2DP 输出并设置 preferred device 后才开始播放，缺少目标则直接失败。
- 原固定中文 PCM“郑州明天的天气怎么样”重复3遍，约6.271秒；AudioTrack 播放帧计数100332，与预期一致。
- 实际 route_name=AirPods Pro、route_type=8（TYPE_BLUETOOTH_A2DP）、route_id=21，测试 `OK (1 test)`，约8.02秒。
- 用户明确确认“听到了，声音清楚”。没有修改系统或耳机音量。
- 播放前后A2DP累计dequeue计数604→1063；flushed/dropped/dropouts及underflow计数均为0。累计值包含系统其他音频活动，不把差值全部归为测试语音。

## 录音

- 使用已安装的 BluetoothActivity“测试耳机麦克风5秒”入口，主动连接 AirPods Headset Profile，再请求 SCO。
- 20:31:58左右HFP连接开始，约0.36秒后回到Disconnected；20:31:59请求SCO，20:32:07报告sco_timeout=true。
- 最终HeadsetService.mCurrentDevice与mActiveScoDevice均为null，A2dpStateMachine仍为Connected。
- 该入口仅在SCO_CONNECTED后启动录音；本次未满足条件，没有启动有效蓝牙耳机录音，也没有使用板载录音冒充。没有进入真人录音阶段，不存在录音识别或回放通过的结论。
- 已保留配对关系；没有删除绑定、重新配对或修改蓝牙配置文件角色。当前媒体连接保留。

## 变更与验证范围

只新增 board 测试源码及本报告，原手机版和主应用实现没有修改。测试APK编译、安装、开发板仪器测试通过。主观听感由用户确认；蓝牙录音失败，根因尚未定位。进一步需要检查板端HFP协商/协议日志及兼容性，或用其他已知可用的HFP耳机做对照。

原始证据：playback.json、profiles.txt、microphone-attempt.txt、a2dp-counters.txt。
