# 独立 Vosk 文件转文字及队列丢弃排查（2026-10-28）

## 结论

已在序列号000000c4cddac462开发板使用现有0.3.0独立ARMv7测试APK完成两组自由听写文件测试，每组6轮，12轮均完整得到“郑州 明天 的 天气 怎么样”。测试不创建AudioRecord，不经过实时采集队列；运行时检查声卡捕获状态为closed。未加载Qwen，未使用天气词表，只有一个Vosk识别器。由此确认该模型/原生库能正确处理这份清晰文件，不能外推为所有语音准确率或麦克风输入已正常。

输入为已有samples/zhengzhou.pcm，16kHz/单声道/PCM16、33444采样/2.09025秒；来源、预期文字和SHA256见sample.json。本轮未修改、归一化或重采样输入，首组在测试中额外追加3秒数字静音，第二组不追加。WAV仅加文件头以便本机回听，位于/private/tmp/imx7-file-vosk-20261028/known-clear-zhengzhou.wav，不上传。两组每轮reset，各执行3次不限速和3次按输入时钟供帧；不是长时连续语音测试。

## 真机结果

| 输入 | 全文正确 | 首轮RTF | 后续5轮RTF | 暖态最长帧 | paced最大完成滞后 | PSS采样峰值 |
| --- | --- | --- | --- | --- | --- | --- |
| 2.09秒清晰语音+3秒静音 | 6/6 | 2.844 | 0.683–0.718 | 313ms | 302ms | 143.0 MiB |
| 2.09秒清晰语音、不追加静音 | 6/6 | 6.936 | 0.729–0.806 | 327ms | 317ms | 134.9 MiB |

RTF为解码耗时/音频时长，不含模型加载；首轮存在4.86–5.41秒单帧停顿，因此录麦前仍需预热。实时麦克风之前已执行预热，不能把这里的冷启动停顿直接当作上一轮丢音的原因。PSS含仪器测试开销，为采样峰值而非模型独占或精确峰值。paced最大完成滞后含当前帧耗时，不能直接等同应用队列等待。

两组AndroidJUnitRunner均输出OK(1 test)，耗时47.214秒/34.331秒。原有自动断言只要求每轮有final；本轮另外读取所有finals，对去空格后的预期全文逐轮核对，12轮全部一致，summary.json记录该核对。

## 上一轮实时丢弃定位

对应previous-live-session.json，以下为计数可直接确认的事实：

- 实际采集1920000采样，解码1877760采样，丢弃42240采样（2.64秒）；1920000=1877760+42240。
- overflow_dropped_samples=0：不是ArrayBlockingQueue.offer因32帧容量满而失败。
- stale_dropped_samples=42240：全部来自解码前“等待超过1500ms且还有较新帧则丢弃旧帧”的主动策略。
- max_queue_frames=23，容量32；约1.84秒PCM的积压尚未占满队列就可能超过1.5秒延迟阈值。
- 停止后queue_frames=0，队列已排空；仪器断言“Queue did not drain”实际是captured==decoded不成立，漏掉了策略丢弃的计数，错误信息不准确。测试目前只先检查overflow计数，没有单独检查stale计数。
- 平均RTF0.838不能证明无突发卡顿。单帧最高1289.83ms，最大实际记录队列等待1618ms；发生20次采样序号断开重置。

以下是代码层面的解释和待验证项：

- MicSession解码帧是80ms输入，处理时间并不均匀；短时卡顿与其他工作/已有积压叠加可能越过1.5秒阈值。已明确是哪条代码路径丢弃，但没有丢弃事件的时间线，不能把某一次停顿精确归因于CPU、GC或I/O。
- 采样序号断开会reset，避免把断裂PCM拼接成连续句子，但频繁重置可能损害连续识别；这不是已量化的因果性能分析。
- decode_rtf统计不含断流reset、报告写盘和全部线程调度开销，因此不能把RTF<1作为整条实时管线无丢音的保证。
- 文件基准直接解码，没有真实生产者/消费者队列，不能声称已在文件回放中复现或修复实时队列问题。

## 建议下一步

先增加逐次丢弃、队列年龄、单帧解码及reset耗时的时间线，并把测试区分为“样本守恒/排空”和“无丢弃”断言。随后用清晰文件按时钟连续供帧，经过真实实时队列，在不接麦克风的条件下验证突发积压及恢复；再决定1.5秒主动丢弃阈值、保完整或保低延迟的策略。仅扩大队列容量不能解决当前先触发的年龄阈值，也不能保证持续超实时负载下无丢音。

本轮没有修改应用/测试代码、构建或安装APK，没有刷机、改系统库或操作手机版。仅运行现有真机测试并新增报告/JSON证据。当前未修复队列策略、未验证长时连续文件输入或更多录音的识别准确率，不进行真实天气查询或播报。

## 复测命令

在adb命令前显式指定开发板序列号，两组依次执行并及时导出benchmark-single.json，防止覆盖：

```sh
adb -s 000000c4cddac462 shell am instrument -w -e class cn.aihongbo.voiceweather.board.VoskBenchmarkTest -e mode single -e profile dictation -e silence_ms 3000 cn.aihongbo.voiceweather.board.test/androidx.test.runner.AndroidJUnitRunner
adb -s 000000c4cddac462 shell am instrument -w -e class cn.aihongbo.voiceweather.board.VoskBenchmarkTest -e mode single -e profile dictation -e silence_ms 0 cn.aihongbo.voiceweather.board.test/androidx.test.runner.AndroidJUnitRunner
```
