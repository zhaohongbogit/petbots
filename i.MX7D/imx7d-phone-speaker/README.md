# iMX7 → 小米手机扬声器：局域网原型

手机接收 iMX7 发来的音频并用内置扬声器播放，回传播放状态。与原先手机向 iMX7 发送音频的蓝牙链路方向不同。

## 使用

1. 两台设备连接同一局域网。
2. 手机打开 **iMX7 手机音箱**，保持页面在前台。
3. iMX7 打开宠物测试应用 → **手机音箱**。
4. 点击 **测试音 → 手机扬声器**，应听到三段提示音，总文件时长 3 秒。
5. **现有录音 → 手机扬声器** 可发送测试应用私有目录中已有的录音，不会重新录音。
6. 手机音量键调节媒体音量；停止按钮取消正在进行的播放。离开手机页面会关闭接收和播放。

## 构建、部署

沿用本机 SDK build-tools 35.0.0、android-29 平台与 JDK，无外部 Java 依赖。

```sh
bash i.MX7D/imx7d-phone-speaker/build.sh
bash i.MX7D/imx7d-pet-screen/build.sh
adb -s 9c79ecb0 install -r i.MX7D/imx7d-phone-speaker/build/phone-speaker.apk
adb -s 000000c4cddac462 install -r i.MX7D/imx7d-pet-screen/build/pet-screen.apk
python3 i.MX7D/imx7d-phone-speaker/provision_lan.py
```

配对脚本读取两台已知设备 wlan0 IPv4，生成独立的随机局域网凭据并配置到两端私有设置；不输出凭据，不使用硅基流动密钥。IP 变化时重新运行脚本。更换设备需修改脚本设备序列号。

手机应用最低 API 28，iMX7 客户端最低 API 27。手机 APK 的 targetSdk 为 29，仅用于当前设备开发验证，不是应用商店发布配置。

## 协议与边界

- 手机监听当前 Wi-Fi IPv4 的 TCP 8765，校验 iMX7 源 IP 和 `X-Pet-Key`。
- `POST /play`：定长原始音频 body，44 字节～2 MiB；接受后返回任务 id。
- `GET /status?id=...`：返回 accepted / preparing / playing / completed / failed / cancelled 状态、时长和实际音频路由。
- 一次只播放一个任务，忙时返回 409；客户端不自动重试 POST，避免重复播放。
- 使用 MediaPlayer 请求内置扬声器，播放时检查 getRoutedDevice。若实际路由到其他设备，停止并报错。
- 音频时长最多 30 秒；播放超时 35 秒；接收文件在播放结束、失败或页面退出时删除。
- 这是可信局域网内的 HTTP 原型，无公网入口、云端上传或后台常驻服务。尚不提供 TLS、自动发现、动态 IP 更新、锁屏播放。

## 2026-09-08 设备验证

手机 Redmi Note 12 Turbo，Android API 35，192.168.211.175；iMX7 192.168.211.22。

- 两端构建、签名校验、安装、启动通过。
- 3 秒 WAV 测试音从 iMX7 发出：96044 字节，手机 duration=3000 ms，实际 route=phone_speaker；完成回执返回 iMX7。
- 首轮上传/接受约 2222 ms、总流程约 6123 ms；第二轮约 1330 / 5174 ms。这是设备日志单次计时，不是稳定延迟基准。
- 现有 AAC 录音 9972 字节传输后，手机报告 duration=813 ms、route=phone_speaker，并触发完成回调。原录音时长异常未在本任务中修复。
- 缺少凭据的局域网请求返回 403。
- 手机页面退出后发送失败，iMX7 明确显示未完成；重新进入接收页面后测试音可再次播放。
- 尚待用户确认真实可听效果、音质；未进行持续压力、锁屏后台、网络漫游测试。

MediaPlayer 路由核对依据： https://developer.android.com/reference/android/media/MediaPlayer

最终新版复测：测试音 3000 ms，输出 phone_speaker，iMX7 接受耗时 1967 ms、完成总耗时 5644 ms。截图与定向事件日志保存在 build/verification/。


新增“给小七发文字”入口（TextChatActivity），使用手机中文键盘向 iMX7 对话页发送文字。进入文字页会暂停本应用的音频接收；模型调用由 iMX7 完成，手机不保存代理密钥。
文字输入框为单行发送模式，点击软键盘“发送”或按实体 Enter 会直接提交；页面发送按钮仍可使用。

Android 模拟器通过宿主机 NAT 访问 iMX7。安装两端最新 APK 后运行 `python3 i.MX7D/imx7d-phone-speaker/provision_emulator.py emulator-5554`，会增加一组独立的模拟器文字调试配对并保留实体手机配对。调试版本不限制请求来源 IP，但仍校验随机配对凭据。该配置只保证模拟器到 iMX7 的文字和作业请求；iMX7 无法直接回连 NAT 内的模拟器，因此不用于“iMX7 → 模拟器扬声器”链路。
