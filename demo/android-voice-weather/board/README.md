# ARMv7 开发板 Vosk 验证版

独立 `:board` 模块，包名 `cn.aihongbo.voiceweather.board`；现有 `:app` 手机代码和构建配置不变。仅打包 Vosk 0.3.75、JNA 5.18.1 的 ARMv7 库，共用原应用的只读 assets。没有本地 Qwen、网络、天气执行或 TTS。0.4.1 增加完整诊断录音、逐次读取计时和原始录音离线复放，保留0.4.0队列优化，原固定 PCM 基准测试继续保留。

## 真实麦克风监听（0.4.1）

打开应用，选择天气词表或自由听写、`VOICE_RECOGNITION` 或 `MIC` 音源、系统默认或指定输入设备，点击“预热并监听 120 秒”，首次需授权麦克风。先用固定 PCM 完成两轮解码预热并 reset，然后才启动录音；预热结果不会混入真实麦克风文字。界面显示临时转写、自然句末结果、采集量、解码量、队列与能量。不会自动播报或外放。天气词表使用 Vosk 动态小词表，覆盖郑州、北京、上海、广州、深圳、杭州，今明后天的天气、下雨和温度相关词，包含 `[unk]`；并非通用听写，也不保证拒绝所有词表外人声或噪声。词表匹配不能作为执行天气操作的授权。自由听写仍可选择。

录音固定 16kHz 单声道 PCM16，每帧 1280 采样；独立高优先级采集线程持续读取，解码使用唯一识别器。AudioRecord 缓冲至少 2秒，应用队列上限 64帧（完整帧时约 5.12秒），队列满丢弃新帧并累计精确采样数，不阻塞采集。发现入队音频采样序号断开后重置解码器，不把缺失前后的片段拼成连续语音。1.5 秒队列等待仅告警，保持已有语音连续性；仅在输入尚未结束、当前等待超过 1.5 秒且待处理队列至少 62 帧时，批量裁剪旧积压到约 500ms，并按采样序号重置一次。队列满仍丢弃新帧并精确统计。停止输入后不再因帧龄裁剪，排空已有队列。这是有损压力恢复，不能保证持续超载时不丢音；溢出与裁剪分别计数，合计计入 `queue_dropped_samples`。每20秒已解码音频强制 final/reset 一次，限制持续解码状态增长；自然空端点也不会推迟这次重置，可能切在句中，结果明确标记 `segment_limit`。结束后排空已入队 PCM，强制尾句标记为 `stop_tail`。点击停止、120秒到期、进入后台或销毁页面会停止录音；预热期间停止也不会随后打开麦克风。

每3秒采样进程 PSS/RSS/VmSwap 和原生堆；约每5秒及停止时原子更新 `files/mic-live.json`。报告保存本地识别文字与诊断数据。默认不保存原始音频；勾选诊断录音后，先将完整诊断PCM留在内存（最多120秒，约3.66 MiB），采集/识别结束后写 `files/mic-diagnostic.wav`，不在实时采集时写盘。WAV 在入队前截取，便于独立排查队列之外的音质问题。每次启用并成功完成后覆盖旧 WAV，未启用时旧 WAV 不删除，是否属于本轮须以 JSON 的 `diagnostic_wav` 字段为准。每秒记录 RMS、峰值、DC 均值，无软件增益或降噪，不上传。启用录音时按实际采样数结束（120秒为1920000采样），墙钟保护为请求时长+15秒；不填静音、不伪造缺失采样，实际墙钟可能更长。手动停止则保存已有部分。逐次读取计时最多4096条，以first_sample对齐原始PCM；报告复制数组快照后释放采集共享锁，再序列化和写盘。新一轮覆盖最新报告，需先导出需保留的旧报告。录音硬件时间戳能否使用取决于 HAL；不支持时记录错误次数，不中断录音。

复测真实麦克风（明确指定目标设备，先完成下方构建安装）：

```sh
adb -s <serial> shell pm grant cn.aihongbo.voiceweather.board android.permission.RECORD_AUDIO
adb -s <serial> shell am instrument -w -e class cn.aihongbo.voiceweather.board.MicrophoneDeviceTest#warmedMicrophoneSession -e seconds 120 cn.aihongbo.voiceweather.board.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> exec-out run-as cn.aihongbo.voiceweather.board cat files/mic-live.json > mic-live.json
```

仪器测试只断言采集量、读取错误、应用队列丢弃和排空。不能因为测试通过就推断输入有有效语音、识别准确或底层没有丢音：还需核对能量、真人说话与文字、AudioFlinger overrun、硬件时间戳及采样量/墙钟偏差。`decode_rtf` 含静音，不代表纯语音处理速度；采样与报告写盘的开销不计入该字段，但会体现在队列等待和采集连续性中。

后台停止另用 `-e class cn.aihongbo.voiceweather.board.MicrophoneDeviceTest#backgroundStopsMicrophone` 单独执行，会预热、真实录音约3秒后把页面移到后台，并检查采集确实停止。各次运行均覆盖 `mic-live.json`，应逐次导出。

## 构建与安装

在 `demo/android-voice-weather` 执行，`<serial>` 必须替换为目标开发板序列号：

```sh
./gradlew :board:assembleDebug :board:assembleDebugAndroidTest :board:lintDebug
adb -s <serial> install --no-streaming -r board/build/outputs/apk/debug/board-debug.apk
adb -s <serial> install --no-streaming -r -t board/build/outputs/apk/androidTest/debug/board-debug-androidTest.apk
```

构建完成后再安装，避免安装期间 APK 被重建覆盖。不使用 `connectedAndroidTest`，避免自动卸载测试应用和数据。

## 单、双识别器测试

依次执行，等待每组结束；不要并行运行。每次 instrumentation 启动新的目标进程，模型在组内共享，组间不共享进程。为比较顺序影响，可按 single → dual → single 顺序复测。

```sh
adb -s <serial> shell am instrument -w -e class cn.aihongbo.voiceweather.board.VoskBenchmarkTest -e mode single cn.aihongbo.voiceweather.board.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> exec-out run-as cn.aihongbo.voiceweather.board cat files/benchmark-single.json > benchmark-single.json
adb -s <serial> shell am instrument -w -e class cn.aihongbo.voiceweather.board.VoskBenchmarkTest -e mode dual cn.aihongbo.voiceweather.board.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> exec-out run-as cn.aihongbo.voiceweather.board cat files/benchmark-dual.json > benchmark-dual.json
```

- 同一个 `small-cn-0.22` 模型；没有生产代码中预先创建的批量识别器。
- 单识别器：无约束转写，开启词时间。
- 双识别器：无约束转写 + `["阿 洛","[unk]"]` 唤醒语法识别器，共享模型。按生产代码顺序调用两个解码器的 accept/result/final/reset。
- 固定 `samples/zhengzhou.pcm`（约 2.09 秒）+ 3 秒静音，16 kHz、PCM16、单声道；每帧最多 1280 采样。输入没有唤醒称呼，不验证声学唤醒准确率。
- 不限速连续处理 3 轮，随后按音频时钟供帧 3 轮。每轮重置解码器，但不重载模型；保留每轮数据观察冷暖差异。
- `rtf` 为 accept/result/final 总耗时除以输入音频时长，包含静音；不是纯有声片段 RTF。
- `frame_p95_ms`、`frame_max_ms` 和超过 80ms 的帧数衡量突发卡顿；`max_completion_lag_ms` 是相对计划供帧时刻的最大完成滞后，包含当前帧解码时间。
- PSS/RSS 为整个测试进程的采样，含仪器测试开销；不能当作模型独占内存或精确峰值。存在采样开销，也不能直接当成完整应用的资源占用。

补测更密集的输入：在每种模式的 instrumentation 命令中加入 `-e silence_ms 0`，然后分别导出为 `benchmark-single-dense.json`、`benchmark-dual-dense.json`。此时不追加 3 秒静音，末尾强制读取 final；不是自然端点延迟测试，也不是跨句长时连续输入。初版默认测试的报告没有 `tail_silence_ms` 和 `VmSwap` 字段，追加静音固定为 3000ms；后续版本明确记录这两项。

## 验证边界

固定 PCM 基准测试只回答 ARMv7 原生库能否在该板加载、固定 PCM 能否识别，以及单/双解码器的短时资源和处理速度。真实麦克风测试单独记录。当前没有后台录音、唤醒证据解析、回声消除、TTS、语义或真实天气验证。可选诊断 WAV 仅用于本地音质排查。模型文件复制和加载耗时独立记录，不计入 RTF。

设备实测原始 JSON 和结论见 `reports/2026-10-27/`。
真实麦克风实测见 `reports/2026-10-27-microphone/`。

## 优化验证命令

固定音频天气词表对照：在原单识别器基准命令加 `-e profile weather`；默认 `dictation` 保留原基准路径，天气词表测试额外核对目标句全文。每次导出 `benchmark-single.json` 后再运行另一组，避免覆盖。

真实耳麦仪器测试可加 `-e profile weather -e save_audio true -e seconds 60`；最多120秒保留原始音频，测试还检查 WAV 尺寸和 RIFF 头。测试通过只代表读取、队列和文件结构通过，准确率、噪声和底层丢音仍须结合真人结果评估。性能与本轮结果见 `reports/2026-10-28-optimization/`。

## 真实队列文件回放（0.4.0）

`FileQueueReplayTest` 只替换生产者：把已知清晰的 `samples/zhengzhou.pcm` 加 1 秒静音循环，按16kHz/80ms帧时钟送入 **MicSession 同一个 ArrayBlockingQueue、同一个 Vosk 解码循环**，不创建 AudioRecord。界面保持前台，保留报告/内存采样开销。仅仪器测试可选择旧1.5秒策略及其32帧容量；手机版和共享模型不变。报告为 `files/queue-replay.json`，不覆盖 `mic-live.json`。

```sh
adb -s <serial> shell am instrument -w -e class cn.aihongbo.voiceweather.board.FileQueueReplayTest#pacedFileThroughRealQueue -e seconds 120 -e stall_ms 1600 -e legacy true -e expect_drops true cn.aihongbo.voiceweather.board.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> exec-out run-as cn.aihongbo.voiceweather.board cat files/queue-replay.json > legacy-replay.json
adb -s <serial> shell am instrument -w -e class cn.aihongbo.voiceweather.board.FileQueueReplayTest#pacedFileThroughRealQueue -e seconds 120 -e stall_ms 1600 -e legacy false -e expect_drops false cn.aihongbo.voiceweather.board.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> exec-out run-as cn.aihongbo.voiceweather.board cat files/queue-replay.json > pressure-replay.json
```

每次测试需立即导出报告，避免覆盖。`stall_ms` 是明确的人为故障注入，只阻塞解码线程，在输入墙钟第15/45/75/105秒附近各一次、最多4次；0为不注入。线程调度可能使实际触发时间略晚，以事件记录为准。不能把故障注入当作自然性能表现。`legacy` 只影响测试文件回放，应用录麦默认使用新策略。

事件数组上限4096，超出记 `omitted_events`。记录每秒队列、迟到帧、慢解码、丢弃、重置、压力恢复和注入时序。`value` 单位：丢弃为采样数，lag/decode/reset/stall为毫秒，queue_sample为帧数。RTF包含断流reset，不包含人为暂停、写报告等开销；另报worker墙钟和报告写盘累计耗时。

断言区分队列排空、`采集=解码+丢弃`守恒、溢出丢弃、过期/压力裁剪丢弃；预期过载测试仍必须排空和守恒。无损测试另核对已知整句命中数量。`stopDrainsAgedTail` 在解码暂停期间停止输入，验证老尾帧不再被年龄策略裁剪。文件回放不证明真实麦克风音质、硬件连续性或天气业务。

## 完整录音离线复放（0.4.1）

先执行120秒真实麦克风测试并导出报告与WAV；即使实时无损断言失败，只要原始WAV完整、SHA与报告匹配，仍可执行离线复放。离线复放严格要求本应用生成的16kHz/mono/PCM16标准44字节头WAV，恰好1920000采样。它逐帧直接送单Vosk自由听写，保留两轮预热和20秒分段重置，不创建AudioRecord，不使用实时队列，也不丢弃任何原始采样。

```sh
adb -s <serial> shell am instrument -w -e class cn.aihongbo.voiceweather.board.MicrophoneDeviceTest#warmedMicrophoneSession -e seconds 120 -e profile dictation -e save_audio true cn.aihongbo.voiceweather.board.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> exec-out run-as cn.aihongbo.voiceweather.board cat files/mic-live.json > full-live.json
adb -s <serial> exec-out run-as cn.aihongbo.voiceweather.board cat files/mic-diagnostic.wav > full-original.wav
adb -s <serial> shell am instrument -w -e class cn.aihongbo.voiceweather.board.RecordedAudioReplayTest#fullRecordedPcmOffline cn.aihongbo.voiceweather.board.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> exec-out run-as cn.aihongbo.voiceweather.board cat files/recorded-replay.json > recorded-replay.json
```

源WAV和实时报告的SHA256须一致，复放后再核对文件未变。结果记录每帧first_sample、耗时、句末、20秒reset与内存；技术断言通过不等于转写准确。通过first_sample可将离线慢帧与实时原始采样及read_timings对齐。录音结束条件现为采样目标，因此不能直接将其墙钟时长与旧版固定墙钟录音作完全同条件比较。
