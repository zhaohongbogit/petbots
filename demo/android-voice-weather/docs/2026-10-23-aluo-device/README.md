# 阿洛唤醒与模拟天气真机验证（2026-10-23）

## 当前结论

主 APK 与测试 APK 已构建并安装到 Xiaomi/POCO 23049RAD8C（Android 15，设备序列号 `9c79ecb0`）。本轮未执行真实天气请求或录音上传。

**声音唤醒存在已复现的失败；文字命令入口、真实 Qwen/TTS、模拟天气确认流程和本次扬声器回声用例已完成验证。** 页面用例的唤醒输入为文字注入，不能把它与整条真人声音交互链路通过混为一谈。

## 已完成的检查

- `:app:assembleDebug`、`:app:assembleDebugAndroidTest` 构建成功。
- `:app:testDebugUnitTest` 共 46 项，0 失败、0 错误。
- `:app:lintDebug` 完成。APK 大小、SHA-256 与音频来源见 `manifest.json`。
- 通过 `android run` 安装并启动主应用；通过 `adb install -r -t` 安装设备测试 APK，保留原有数据和模型文件。
- 首次设备 UI 检查发现图案锁屏，使用者随后手动解锁。解锁后完成前台模拟流程与回声测试，未输入、读取或绕过锁屏凭据。
- 独立真实 Qwen 测试 `QwenSemanticDeviceTest#measureWholeUtteranceSemantics` 通过：11 个用例，覆盖天气、城市日期、闲聊、拒绝、不支持日期及 yes/no 确认判断，总测试耗时 127.1 秒，单次推理 6688–13231ms。原始输出见 `qwen-semantic-report.json`。它没有运行 Activity、TTS、麦克风或天气网络请求，不能替代前台闭环验证。

## 原生 Vosk 唤醒测试

测试输入由 macOS Tingting 离线语音生成，语速 165，经 ffmpeg 转为 16kHz 单声道 PCM16。它们是可复现的合成样本，**不是人声麦克风、远场或扬声器回声验证**。原始音频位于 `app/src/androidTest/assets/wake/`，仅随测试 APK 打包。

初次批量原生解码实测：

| 输入音频 | Vosk 原文 | 唤醒入口 |
| --- | --- | --- |
| 阿洛 | 那 咯 | IGNORE，失败 |
| 阿洛，上海明天天气怎么样 | 落 上海 明天 天气 怎么样 | IGNORE，失败 |

详见 `wake-native-batch-report.json`。随后使用真实 Vosk Stream 接口逐个送入 80ms 帧，3.727 秒完成复测，两条原文与上表完全相同，仍然失败，见 `wake-native-report.json` 和 `wake-native-instrumentation.txt`。测试断言失败被保留，未将这些错词自动替换成“阿洛”，也未将失败测试改成无条件通过。

当前入口严格依赖完整句开头为“阿洛”。当 Vosk 识别错字或漏字时，不会进入 Qwen，因而不会回应“收到”。这份证据只说明上述两条样本失败，不能外推为所有真人、语速或音色都失败。后续若要提升声音唤醒能力，应单独评估专用唤醒检测或明确授权的词表方案，并保留原始转写。

## 前台用例与验证边界

新增 `WakeCommandDeviceTest`，将以下用例分开记录：

1. `nativeVoskWakeAccuracy`：实际 Vosk 接收测试 PCM 的 80ms 音频帧，检查未经改写的文字能否通过唤醒入口，写入 `wake-native-report.json`。
2. `acknowledgementAndSimulatedWeather`：向实际 Activity 注入完整文字，使用真实 Qwen 与离线 TTS，检查“收到”播放、回应后的模型交接、拒绝、单独唤醒、Vosk 肯定音频确认、模拟天气播报和回应中重置。该用例不证明麦克风正确识别了“阿洛”。
3. `speakerMicrophoneEchoAndTaggedEchoCannotTriggerTasks`：开启真实麦克风，让设备扬声器播放含唤醒称呼和“是的”的语句，同时单独标记一条确定可识别的回声注入。报告分别记录真实麦克风转写数量、标记回声保护、额外回应和 Qwen 调用。真实采音中如果没有可辨认的回声转写，不能据此宣称已证明所有回声都被抑制。测试临时使用媒体音量一半，结束恢复原音量。

解锁后的验证结果：

| 检查 | 实际结果与边界 |
| --- | --- |
| 普通讲话 | 文字注入“上海明天天气怎么样”未回应、未调用 Qwen |
| 命令接收 | 文字注入“阿洛，上海明天天气怎么样”，观察到真实离线 TTS 播放“收到”；回应期间模型交接仍在等待 |
| 任务识别 | Qwen 输入已移除唤醒前缀，识别为上海明天天气，并实际播放确认询问 |
| 拒绝 | 直接回答“不用”，Qwen 判断拒绝；没有额外回应或天气请求 |
| 单独唤醒 | 文字注入“阿洛”，回应结束开启 8 秒窗口，下一句不带唤醒称呼的命令被接收 |
| 同意与模拟天气 | 播放既有肯定 PCM 给原生 Vosk，识别后进入确认流程，实际显示并播报上海明天模拟天气；不是从真人麦克风说出同意 |
| 回应中重置 | 尚在“收到”回应阶段重置，旧任务未启动 |
| 实际扬声器回声 | 媒体音量 75，设备扬声器播放“阿洛，上海明天天气怎么样。是的。”；真实麦克风得到 1 条完整转写“安诺 上海 明天 天气 怎么样 是 的”，被播报重叠保护拒绝，未调用 Qwen、未额外回应 |
| 标记回声保护 | 另行注入正确的“阿洛 上海 明天 天气 怎么样 是的”，明确标记 playback=true，同样仅记录，未触发任务；该部分是确定性保护测试，不是声学识别证据 |
| 外部请求 | 页面与回声用例均确认真实天气请求计数为 0；没有执行录音上传 |

首轮 2 个前台用例共 50.971 秒：模拟天气流程通过，回声用例因测试脚本在主线程中嵌套调用 `runOnMainSync` 失败。修正测试脚本，业务代码未改动；单独回声复测 13.727 秒，通过。保留首轮与复测证据：

- `wake-workflow-report.json`：页面流程各项断言和实际 Activity 日志。
- `wake-ui-echo-instrumentation.txt`、`wake-echo-first-attempt.json`：首轮结果及测试脚本错误。
- `wake-echo-retest-instrumentation.txt`、`wake-echo-report.json`：回声复测通过及真实麦克风转写。
- `wake-device-screen.png`：实际页面在任务询问阶段的截图。

TTS 播放状态及真实扬声器到麦克风的采音均已观察到，但没有人工听审“收到”的音质或音量。回声结果仅覆盖本机本次音量、距离、语句与约 6 秒尾部观察窗口，不能外推所有环境、延迟或真人插话；真人“阿洛”、远场噪声及长时间稳定性仍未验证。

复测前台用例时保持解锁，只运行模拟用例，不传入任何 `live=true` 参数：

```bash
adb -s 9c79ecb0 shell am instrument -w \
  -e class 'cn.aihongbo.voiceweather.WakeCommandDeviceTest#acknowledgementAndSimulatedWeather,cn.aihongbo.voiceweather.WakeCommandDeviceTest#speakerMicrophoneEchoAndTaggedEchoCannotTriggerTasks' \
  cn.aihongbo.voiceweather.test/androidx.test.runner.AndroidJUnitRunner
```

`nativeVoskWakeAccuracy` 是单独的准确性门槛；即使页面文字注入用例通过，也不能覆盖或抵消它的失败。
