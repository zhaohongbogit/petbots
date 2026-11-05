# i.MX7D HFP AG 启用研究

2026-10-27。范围：补齐诊断应用权限，查找源码并形成固件修复路径；不刷机、不替换系统库、不修改手机版。

## 本轮完成

`../imx7d-pet-screen/AndroidManifest.xml` 新增 `android.permission.MODIFY_AUDIO_SETTINGS`。本地原 Manifest 经 aapt 编译后与设备已装包的二进制 Manifest 完全一致，签名证书也一致，因此本轮只重新生成 Manifest，保留设备已装 `classes.dex`，重新对齐、签名并覆盖安装，避免把该目录已有的其他未提交 Java 改动带入设备。

- 产物：`../imx7d-pet-screen/build/pet-screen-audio-permission.apk`。
- v2/v3 签名校验通过，DEX SHA-256 与修改前完全相同；没有重编译业务 Java。
- 设备安装成功，`dumpsys package` 明确显示 `MODIFY_AUDIO_SETTINGS: granted=true`。
- 重新点击耳机麦克风测试：该次日志中没有音频设置权限拒绝；Headset 连接仍立即断开，SCO 仍超时，未产生有效蓝牙耳机录音。
- 没有删除绑定、刷机、remount、替换蓝牙或音频系统库；完整 HCI 日志保持关闭。

APK 哈希与验证摘要见 `permission-verification.json`。源码差异只有新增一条应用权限，研究材料独立放在本目录；其他既有未提交改动保留。

## 设备指纹与匹配标准

设备：`Things/iot_imx7d_pico/imx7d_pico:8.1.0/OIM1.180327.097/5796897:userdebug/dev-keys`，Android Things 1.0.15，API27，ARMv7，安全补丁2019-08-01。

运行内核：`4.9.78-gd7aad6b09483`，构建于2019-08-12。

设备蓝牙模块：`/system/lib/hw/bluetooth.default.so`，Android ident API27，ELF build-id `73ececa52c7ebbd9515c20a2148de0a3`；SHA-256记录在 `sources.lock.json`。

“匹配”必须区分：运行内核的同一提交、相同API/模块布局的参考源码、完整原厂产品源码，以及可直接替换的二进制。找到同一型号或类似标签不能推导出库文件可直接替换。

## 源码查找结果

| 来源 | 定位结果 | 匹配程度 |
|---|---|---|
| [Google NXP i.MX 4.9 BSP 内核](https://android.googlesource.com/platform/hardware/bsp/kernel/nxp/imx-v4.9/+/d7aad6b094830bf4cafd113cd8e34efd85f9c965) | `d7aad6b094830bf4cafd113cd8e34efd85f9c965`；源码Makefile为4.9.78；多个Things发布标签指向此提交 | **与运行内核的Git提交一致**；不证明构建配置和产物逐字节一致 |
| [Google NXP U-Boot BSP](https://android.googlesource.com/platform/hardware/bsp/bootloader/nxp/uboot-imx/+/d2b6658d4d16855b85c9802f279c6dec52664dea) | Things 1.0.14对应公开提交 | 同代参考，未验证与已装bootloader一致；本任务不需要改bootloader |
| [Things公开manifest](https://android.googlesource.com/platform/manifest/+/refs/tags/android-o-mr1-iot-release-1.0.14/default.xml) | 找到1.0.14及其他IoT标签；本次查询没有找到1.0.15公开tag | 包含内核、U-Boot及平台项目，未包含`iot_imx7d_pico`完整产品/供应商HAL构建目录，不能视为可复现完整固件 |
| [Things原生蓝牙栈](https://android.googlesource.com/platform/system/bt/+/b9ecf192dea541f0f5625fa4d3dce10996257705) | 已下载1.0.14标签 | **排除直接替换**：其模块名为`libbluetooth`，依赖bluetooth.audio@2.0、keymaster@4.0等，和设备的`hw/bluetooth.default`布局不同；更早Things 1.0.0标签也使用`libbluetooth` |
| [AOSP 8.1原生蓝牙栈](https://android.googlesource.com/platform/system/bt/+/19547d22872708095470ef4aad7100d4f112b940) | 已下载`android-8.1.0_r74` | API27、模块名`bluetooth.default`及安装路径`hw`与设备更接近，适合接口研究；**不是精确匹配1.0.15的已验证源码** |
| [AOSP 8.1 Bluetooth应用/JNI](https://android.googlesource.com/platform/packages/apps/Bluetooth/+/00febcc68cb6f4d40fc38dbee14c9bb9ae21c7ab) | 已下载同tag | Headset服务和JNI对照基线，未证明与设备APK一致 |
| TechNexion Android manifest | 查询到`tn-o8.0.0_1.0.0-ga`、`tn-o8.1.0_1.3.0_8m-ga` | 后者为i.MX8M版本；没有把普通Android或新硬件BSP当作当前Things系统的匹配BSP |

官方资料另确认：PICO i.MX7D原本采用Android Things BSP，常规i.MX Android BSP当时并未直接支持该板，需要移植。[NXP官方技术支持说明](https://community.nxp.com/t5/i-MX-Processors/Android-for-iMX7D-Pico/td-p/765486)。该说明为历史支持范围，不能替代对当前下载入口与版本的核对。

完整设备BSP、精确1.0.15平台版本、该产品的`bdroid_buildcfg.h`和供应商音频HAL仍未找到，不能宣称已经具备安全生成替换库的条件。

## 本地源码与复现

精确版本、分类和commit见 `sources.lock.json`。`sources/`为忽略的研究参考目录，没有加入应用构建：

- `sources/system-bt`、`sources/Bluetooth`：Things 1.0.14公开标签镜像。
- `sources/system-bt-8.1`、`sources/Bluetooth-8.1`：API27参考快照。
- 没有全量下载AOSP、内核或BSP，也没有构建原生系统库。

```sh
python3 i.MX7D/hfp-research/fetch_sources.py verify
# 缺少本地源码时才下载；只操作sources目录，不调用adb或刷机工具
python3 i.MX7D/hfp-research/fetch_sources.py fetch
```

镜像HEAD与快照的所有已跟踪文件内容已核对到固定提交；脚本不覆盖已有修改。不把整个上游源码复制进项目Git历史。

## HFP AG 的实际启用路径

已确认的协议事实：标准连接路径只查询HSP UUID `0x1131/0x1108`，耳机返回空记录；本机EIR只广播HSP AG `0x1112`，没有HFP AG `0x111f`。独立只读探针显式按HFP HF `0x111e`连接RFCOMM成功并收到`AT+BRSF`，证明耳机HFP入口可用；该探针没有建立完整SLC或SCO。

### 1. 找到产品实际编译配置

AOSP8.1的源码调用链：

- `build/make/core/soong_config.mk`：将`BOARD_BLUETOOTH_BDROID_BUILDCFG_INCLUDE_DIR`写入Soong的`BtConfigIncludeDir`。
- `system/bt/build/fluoride.go`：把该目录加入头文件搜索路径，并定义`HAS_BDROID_BUILDCFG`。
- `system/bt/include/bt_target.h`：包含产品的`bdroid_buildcfg.h`。
- `system/bt/btif/src/btif_hf.cc`：默认`BTIF_HF_SERVICES`已经是HSP与HFP的并集；若产品头文件提前覆盖为HSP-only，默认定义不会生效。

所以不能只把公共源码中的默认值“改成HSP+HFP”——它本来就是这个值。优先在匹配产品源码中查：

```sh
rg -n 'BOARD_BLUETOOTH_BDROID_BUILDCFG_INCLUDE_DIR|BTIF_HF_SERVICES|BTIF_HF_SERVICE_NAMES|BTIF_HF_FEATURES' device hardware vendor system/bt
```

预期配置方向（仅研究示例，尚未应用到任何产品或设备）：

```c
#define BTIF_HF_SERVICES (BTA_HSP_SERVICE_MASK | BTA_HFP_SERVICE_MASK)
#define BTIF_HF_SERVICE_NAMES {"Headset Gateway", "Handsfree Gateway"}
```

还需检查产品覆盖的功能位，不能在未知控制器/音频链路上直接宣告所有codec/eSCO能力。AOSP8.1 `btif_hf.cc` 的init分支选择`BTA_HFP_SERVICE_ID`，`btif_hf_execute_service`通过`BTA_AgRegister`注册上述服务；`bta/ag/bta_ag_sdp.cc`中的HFP主动连接分支查询`0x111e`。这才是修复后必须观察到的行为。

### 2. 验证无电话业务场景的完整SLC

HFP AG与HFP Client角色不同，后者是连接手机的车机角色，不能用角色互换冒充耳机麦克风。需要验证：`AT+BRSF`→`CIND`能力/状态→`CMER`等握手完成，Headset状态到Connected；Things设备没有正常手机Telecom环境，需检查Headset状态机对空电话服务的处理。不得实际拨打电话或创建线上业务来测试。

### 3. 验证SCO音频HAL和路由

当前`/system/etc/primary_audio_policy_configuration.xml`只声明AUX/I²S输入输出，没有声明SCO设备端口。它提示后续可能存在音频策略/HAL缺口，但没有单凭静态文件断定所有动态SCO实现都不存在。

即使HFP SLC修复成功，仍需核对供应商HAL、控制器CVSD/mSBC能力及SCO数据走HCI还是PCM/I²S，验证SCO_CONNECTED、实际`TYPE_BLUETOOTH_SCO`输入、有效人声以及采样连续性。只修改策略XML而没有HAL支持不能作为解决方案。

### 4. 构建与部署边界

取得完整匹配BSP后，先在Linux/AOSP构建环境中构建、检查ARMv7/API27、JNI/HIDL依赖和动态符号，再准备回退材料并评审。不能将公开Things标签的`libbluetooth.so`改名成`bluetooth.default.so`替换，也不能凭内核提交相同就认定用户态库兼容。

本轮没有执行系统库编译、整机镜像构建、刷机或系统库部署。后续部署仍需单独授权。

## 验证分层

- 源码/静态：新增权限、一致性检查和上游源码研究完成；固定版本来源校验通过。
- APK处理：Manifest编译、重新打包、签名校验通过；业务DEX未变。
- 开发板：覆盖安装、权限授予、Activity启动与SCO重试完成；权限拒绝消失，HFP/SCO仍未通。
- 未完成：完整匹配BSP获取、原生库/系统编译、完整HFP SLC、有效蓝牙录音、模拟器/手机版回归；没有天气接口或线上业务操作。
