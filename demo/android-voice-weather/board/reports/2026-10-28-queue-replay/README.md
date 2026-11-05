# ARMv7 真实实时队列回放与丢弃策略优化（2026-10-28）

## 范围与真实路径

目标开发板000000c4cddac462，独立 :board / cn.aihongbo.voiceweather.board，开发板验证版0.4.0(6)。只修改板端模块。测试仅替换MicSession采集生产者：samples/zhengzhou.pcm（16kHz单声道PCM16，2.09025秒，原样）加1秒静音循环，按80ms输入时钟进入真实ArrayBlockingQueue，再进入同一个Vosk自由听写单识别器、临时文字/句末/20秒分段和诊断循环。不创建AudioRecord，不播放声音，不加载Qwen、天气词表或天气业务。仪器测试在回放前、采集中与结束后检查声卡采集状态为closed。

默认对照在输入墙钟15/45/75/105秒各暂停解码线程1600ms；生产者继续。事件明确标记人为注入。不能把注入测试当作自然性能表现，也不能把本轮清晰文件结果外推为所有中文、真实麦克风或全天稳定性。

## 已取得的真机结果

| 版本/策略 | 队列容量 | 输入时长 | 人为暂停 | 溢出丢弃 | 年龄/压力裁剪 | 总丢弃 | 断流重置 | 完整测试句命中 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 旧年龄阈值策略+回放/事件采集 | 32帧 | 120秒 | 4×1.6秒 | 13.60秒 | 32.16秒 | 45.76秒 | 50 | 17 |
| 第一轮压力恢复候选 | 32帧 | 120秒 | 4×1.6秒 | 1.84秒 | 4.56秒 | 6.40秒 | 3 | 35 |
| 最终压力恢复候选 | 64帧 | 120秒 | 4×1.6秒 | 0 | 0 | 0 | 0 | 37 |

三轮均完成输入，计数守恒且结束队列清空。旧策略测试以“预期有丢弃”通过；第一轮压力候选要求无损，实际因溢出丢弃而断言失败，保留pressure-v1原始JSON；最终候选无损测试通过。不能把第一轮的失败隐藏为通过，也不能把旧策略预期丢弃测试的通过解释为无丢音。

最终120秒候选实际输入1920000采样，解码1920000采样；队列采样峰值48/64帧，最大观测等待3776ms，PSS采样峰值160.3MiB，RTF0.756。保留积压意味着瞬时识别延迟可以超过1.5秒，并非低延迟硬保证。测试句命中按final文本去空格后匹配；不是完整语音准确率指标，20秒强制分段和末尾截断会影响整句命中。

180秒无注入回放已通过：2880000采样全部解码，丢弃0、断流重置0，整句命中56次；队列采样峰值33/64帧，最大观测等待2608ms，PSS采样峰值162.0MiB，RTF0.732，结束后约0.54秒排空。严重超载（30秒输入，15秒附近注入一次7秒解码暂停）通过预期有损断言：输入480000采样、解码368640采样，溢出30720采样、压力裁剪80640采样，总丢弃111360采样/6.96秒；记录1次压力恢复、2次断流重置，结束队列为空，样本守恒，仍能识别测试句。该测试不是无损通过。

停止保尾通过：在3秒解码暂停期间约16.041秒停止输入，输入256000采样全部解码，丢弃0，最大旧帧等待3129ms，最终排空；事件明确证明停止发生在暂停开始15009ms与结束18010ms之间。

## 证据定位

- legacy-120s-stall1600.json：旧策略在回放4659ms首次stale_drop，待处理约18帧、未满载；第一次人为暂停在15091ms。因此自然文件回放已在注入前复现丢弃。
- 旧策略后续连续断流reset，50次累计约3969ms；出现更长停顿及队列满载。时间线支持存在反馈风险，但不把所有原生停顿因果归于reset。
- pressure-v1-120s-stall1600.json：后段自然解码停顿与人为暂停叠加，原2.56秒队列不足；总丢弃降至6.40秒但仍未达到无损要求。
- pressure-120s-stall1600.json：相同文件/供帧/故障日程，最终64帧候选吸收短时积压，样本全部解码。容量与策略都有变化，不能把收益全部归于单一改动。

## 最终策略与计数

- 1.5秒只告警，不仅因帧龄而丢弃连续语音，界面显示积压恢复提示。
- 队列上限32→64帧，即约2.56→5.12秒PCM，额外原始PCM空间约80KiB（不含对象开销）。这是依据实测叠加停顿选择的有限缓冲，不保证持续超载无损。
- 输入尚在进行且未请求停止，等待超过1.5秒、队列至少62帧时，批量裁剪旧积压到约500ms；若暂无更新音频则保留最后一帧。持续过载仍可能丢弃新帧，所有丢弃分别计数。
- 按采样序号断开重置，避免拼接缺失前后的语音。批量裁剪只产生一次相应重置；另有溢出缺口时仍需重置。
- 输入结束或请求停止后，排空已有帧，不再按帧龄/压力裁剪尾部。旧策略仅在文件回放测试的legacy选项保留，用于对照。
- 测试拆分为：finished/error/readErrors、队列为空、captured=decoded+overflow+trim守恒、overflow=0、trim=0。预期严重超载的测试仍须满足守恒与排空；不能再用captured==decoded替代“排空”。

文件回放不测量硬件时间戳或输入能量，timestamps/audio_levels为空，peak/RMS/零样本率等字段仍为默认值，不能用于文件音质判断；本轮只使用样本计数、已知文字、解码与队列时序作结论。

事件最多4096条，超出显式计数。RTF包括断流reset，但不包括人为暂停、报告写盘和全部调度开销；worker_wall_ms另记墙钟。max_observed_queue_age_ms是裁剪前所见等待峰值，max_queue_age_ms是实际交给识别器的帧等待峰值，均不是整个系统硬延迟上限。PSS为含仪器测试的进程采样值，不是模型独占或精确峰值。报告中的软阈值/停止保尾说明指pressure策略，legacy保留旧行为。

## 文件与验证层级

修改：MicSession.java、board/build.gradle、board/README.md、MicrophoneDeviceTest.java；新增FileQueueReplayTest.java、SessionChecks.java和本报告目录。

本轮没有改手机版、共享模型、系统库或固件，没有刷机、提交Git或发布。板端APK与测试APK构建通过，Lint 0错误/11警告。以下均是真实开发板上的仪器测试，不是模拟器。麦克风无损断言已修正并编译，本轮不重新打开真实麦克风做录音验证。没有验证语义确认、真实天气或TTS。

## 复测

```sh
adb -s 000000c4cddac462 shell am instrument -w -e class cn.aihongbo.voiceweather.board.FileQueueReplayTest#pacedFileThroughRealQueue -e seconds 120 -e stall_ms 1600 -e legacy true -e expect_drops true cn.aihongbo.voiceweather.board.test/androidx.test.runner.AndroidJUnitRunner
adb -s 000000c4cddac462 shell am instrument -w -e class cn.aihongbo.voiceweather.board.FileQueueReplayTest#pacedFileThroughRealQueue -e seconds 120 -e stall_ms 1600 -e legacy false -e expect_drops false cn.aihongbo.voiceweather.board.test/androidx.test.runner.AndroidJUnitRunner
adb -s 000000c4cddac462 shell am instrument -w -e class cn.aihongbo.voiceweather.board.FileQueueReplayTest#pacedFileThroughRealQueue -e seconds 180 -e stall_ms 0 -e legacy false -e expect_drops false cn.aihongbo.voiceweather.board.test/androidx.test.runner.AndroidJUnitRunner
```

每轮结束后立即导出files/queue-replay.json再开始下一轮，避免覆盖。严重超载：使用同一pacedFileThroughRealQueue命令，将参数改为 `-e seconds 30 -e stall_ms 7000 -e legacy false -e expect_drops true`。

停止保尾：

```sh
adb -s 000000c4cddac462 shell am instrument -w -e class cn.aihongbo.voiceweather.board.FileQueueReplayTest#stopDrainsAgedTail cn.aihongbo.voiceweather.board.test/androidx.test.runner.AndroidJUnitRunner
```

最终候选的120秒注入、180秒自然回放、30秒超载、停止保尾测试分别输出OK(1 test)，仪器总耗时144.752/205.410/56.148/43.185秒，含模型加载与预热，不等于输入时长。回放相关采集状态检查通过；最终两张声卡的捕获状态均closed。手机版目录241个源文件/配置/资源SHA256逐一对照无变化（phone-source-check.json）。各组汇总见summary.json，构建产物与ABI见build-artifact.json。

本轮未验证真实麦克风音质或其无损采集断言（该断言已编译、共享检查已由文件测试实测），未进行超过3分钟的自然持续回放，也未验证其他语料/语速/噪声。有限缓冲无法保证持续解码慢于输入时不丢音。
