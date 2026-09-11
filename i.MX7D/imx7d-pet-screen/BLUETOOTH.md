# 蓝牙验证

2026-09-08，在用户确认目标手机和配对操作后进行。

- iMX7 蓝牙适配器开启；新增 BluetoothActivity 可扫描附近设备，扫描不自动配对。
- 临时启用 120 秒可发现模式，从 Redmi Note 12 Turbo 蓝牙设置中选择 iMX7。
- 两端连接状态通过 dumpsys 核验：手机 STATE_CONNECTED / A2dpStateMachine Connected；板端 A2dpSinkStateMachine、AvrcpControllerSM、HeadsetClientStateMachine 均为 Connected。
- 此结果验证手机向板端连接的蓝牙配置文件链路。尚未测试真实音频播放、通话、文件传输、BLE GATT 数据通信、重启重连或板端向蓝牙耳机发送音频。

新增文件 BluetoothActivity.java；Manifest 增加蓝牙与 Android 8 扫描所需的位置权限，构建脚本改为编译全部 Java 文件。
安装构建与签名验证通过。保留用户已授权建立的绑定关系。

## AirPods Pro 输出与麦克风验证（2026-09-09）

- AirPods Pro（尾号 FC:A7）已成功配对。
- 通过 Android Things `BluetoothProfileManager` 将设备从 A2DP Sink/Headset Client 切换为 A2DP Source/Headset，绑定记录在蓝牙服务重启后保留。
- A2DP 已连接，媒体路由为 `bt_a2dp`，设备名为 AirPods Pro，编码为 SBC。
- 小七本地 TTS 播放约 7.4 秒；A2DP 发送 737 包，丢包与下溢均为 0，用户确认实际听到声音。
- AirPods 绑定记录包含 HFP Handsfree UUID `0000111e`，说明耳机硬件声明支持通话麦克风。
- i.MX7 主动连接 Headset Profile 时从 connecting 立即回到 disconnected，SCO 未建立；系统可用录音输入仍只有板载麦克风。因此当前版本不能把 AirPods 判定为已可用麦克风。
- 已在当前 Source/Headset 角色下删除旧绑定并重新配对，A2DP 自动恢复，但 Headset Profile 仍在连接后立即回到 disconnected。
- 系统报告 `isBluetoothScoAvailableOffCall=true`，实际调用 `startBluetoothSco()` 后没有 SCO connected 事件；`mCurrentDevice`、`mActiveScoDevice` 仍为空，无法启动蓝牙麦克风实录。
- 结论：AirPods 硬件通过 UUID 声明支持 HFP 麦克风，但当前 i.MX7D Android Things 8.1 蓝牙栈与其 HFP 连接不兼容；本项目只能确认 AirPods 作为 A2DP 输出可用，不能把它作为语音输入。语音输入继续使用板载麦克风，或后续测试 USB 麦克风/其他 HFP 耳机。
