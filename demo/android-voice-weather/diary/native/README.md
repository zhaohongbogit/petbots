# NXP 本机离线语音处理验证

**当前尚未达到“清晰录音和完整准确逐字稿”的交付要求。** 本目录是可复现的 ARMv7 本机处理后端，没有替换日记应用的录音/转写实现，也没有改变手机版。技术上的全样本处理完成不能视为文字准确率通过。

## 已实现

- `offline_speech.c`：只读取本地16kHz单声道WAV，不打开麦克风，不调用网络。通过 sherpa-onnx C API，在 NXP 上运行 GTCRN 语音增强和 Zipformer 14M 中文识别。
- 保留原音；输出文件已存在时拒绝覆盖，输出权限受 umask 0077 保护。非有限输出样本拒绝写入；必要时仅缩小音频峰值，避免写PCM16时削波。
- 识别逐帧遍历全部原始采样，不使用会丢弃积压帧的实时队列；句末结果带模型时间戳和已处理采样位置。末尾加入0.8秒静音用于模型终结，单独记录，不算作原始音频。
- `process_on_device.sh`：在设备上顺序完成增强和识别。新建私有结果目录，失败保留running标记，成功写processing-complete标记；此标记只表示处理步骤结束，不表示准确率验收。

## 构建和资源

`sh build.sh` 使用 Android NDK 28.2.13676358 构建 API27/armeabi-v7a 工具，依赖项目已有 `app/libs/sherpa-onnx-1.13.8.aar`。`build/`和`models/`沿用工程忽略规则，不提交生成物及模型。

`vendor/sherpa-c-api.h` 来自同版本官方头文件：
https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v1.13.8/sherpa-onnx/c-api/c-api.h

模型来源：
- GTCRN：https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/gtcrn_simple.onnx
- Zipformer 14M：https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23.tar.bz2

Zipformer使用encoder和joiner的int8文件、decoder的fp32文件以及tokens.txt；四个文件放在models/zipformer14m/，GTCRN放在models/gtcrn_simple.onnx。模型说明见 https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html 。

部署目录：`/data/local/tmp/nxp-offline/`，包含可执行文件、lib/、models/及process_on_device.sh。需让动态链接器找到依赖库；执行工具前设置 `LD_LIBRARY_PATH=/data/local/tmp/nxp-offline/lib`。Android应用中的调用、模型管理与录音增益管理尚未集成，不能把临时工具当作已交付的独立应用。

## 2026-10-29 实机验证

设备系统可见内存500436KiB，API27、ARMv7。没有新录音，没有上传声音，没有刷机或修改系统音频配置。

- C源码使用`-Wall -Wextra -Werror`编译通过；两个shell脚本语法检查通过。
- 原Vosk对120秒原音完整离线复放：1920000采样全部送入，RTF约1.394，仍只得到零散词句；无丢帧不等于准确率通过。
- 新引擎已知清晰中文样本：89784采样全部识别，输出与模型官方样本的预期文字一致；仅证明该模型在板上运行正常。
- GTCRN在板上处理120秒录音：160.010秒，输出1920000采样，峰值9997，PCM数字削波0；听感由用户确认，不能用电平指标代替。
- 新中文模型在板上识别同一增强录音：1920000原始采样全部送入，130.485秒，另有12800个终结静音采样；输出仍有可疑词句，不能作为完整准确逐字稿交付。
- 两阶段总计约290.5秒；不是实时性能通过，也未验证全天录音处理能力。
- 已存在的输出文件覆盖保护在真机返回预期退出码11，没有覆盖旧结果。

所有录音与文字草稿只保留在设备和本机临时目录，不纳入仓库：
- 本机 `/private/tmp/nxp-offline-engine/`：增强音频、识别JSONL、明确标为DRAFT的文本、validation.json。
- 设备 `/data/local/tmp/nxp-offline/`：同一增强WAV与识别JSONL，均在NXP生成。

接下来需要用已知文本的真人录音逐字核对，确认主要限制在模拟收音质量还是模型识别能力。在这项验证前，不承诺清晰度、识别完整率、实时能力或全天可用。

## 人声保留对照（2026-10-29）

用户回听完整GTCRN输出确认“噪音消失，人声不清晰”，该增强档位未通过清晰度验收。新增`blend`命令在NXP上将原音与增强音频对齐后混合，不重新录音、不加采集增益。

```sh
LD_LIBRARY_PATH=/data/local/tmp/nxp-offline/lib /data/local/tmp/nxp-offline/nxp-offline-speech blend original.wav enhanced.wav conservative.wav 0.7
```

末尾参数为增强音频比例：0.7表示70%增强音频、30%原音。程序在±512采样范围搜索差分相关性来校验相对延迟；相关性低于0.15则拒绝生成，以免盲目混合引起相消。边缘没有对应增强采样时保留原始采样；输出采样数量不变。需要时只衰减峰值，防止数字削波，不将原音完全静音。

本次真机：120秒/1920000采样，最佳相对延迟0、相关系数0.4945，处理10.151秒；混合输出峰值13397，数字削波0。人声清晰度和转写准确率仍需真人核对，不能仅据混合参数或采样完整性宣布通过。

`process_on_device.sh`可选第三参数传0.7，生成保守混合版后再识别；省略时保持原先完整增强行为，避免在未验收前悄悄更换默认处理。两种档位均处于验证阶段，尚未集成到日记应用。

保守混合版同样在NXP完成全1920000采样转写，用时119.253秒；未跳过采样，文字仍存在可疑片段，尚未完成准确率验收。
