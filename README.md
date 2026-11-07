# kernel

基于 [KernelSU](https://github.com/tiann/KernelSU) 的修改版，用于 Android GKI 设备的内核级 root 管理器。

- **上游**：`tiann/KernelSU`，基线 commit `730f6446`
- **许可证**：管理器部分 **GPL-3.0**（见 `LICENSE`），内核模块部分 **GPL-2.0**（见 `kernel/LICENSE`）
- **本仓库说明**：这不是官方版本，是修改版。改动清单见 [`CHANGES.md`](CHANGES.md)，第三方组件许可见 [`NOTICE.md`](NOTICE.md)。

## 目录结构

```
kernel/                  内核模块源码（GPL-2.0）
uapi/                    内核 ↔ 用户态接口定义
userspace/
  ksud/                  守护进程（Rust），构建期把 8 个 <kmi>_kernelsu.ko 与 ksuinit 内嵌进二进制
  ksuinit/               早期启动 init 替换（Rust，静态链接）
manager/                 Android 管理器（Kotlin + Compose）
  app/src/main/jniLibs/  预编译 native 库（libkernelsu.so / libadbroot.so / libksud.so）
  app/src/main/java/me/weishu/kernelsu/ui/screen/cardkey/   卡密闸门
  app/src/main/java/me/weishu/kernelsu/stealth/             隐藏与呼出
```

## 构建

需要：JDK 21、Gradle 9.7.1、AGP 9.4.1、Kotlin 2.4.20、Android SDK **compileSdk 37**、
NDK（本仓库已改为使用预编译 `jniLibs`，**不需要** NDK/CMake）。

### 1. 管理器 APK

```sh
cd manager
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :app:assembleRelease \
  -PKSU_PACKAGE_NAME=com.evilsu -PKSU_NAME=Evil \
  -PPSU_VERSION_CODE=32707 -PPSU_VERSION_NAME=v3.3.1-custom \
  -PKEYSTORE_FILE=/path/to/your.keystore -PKEYSTORE_PASSWORD=*** \
  -PKEY_ALIAS=*** -PKEY_PASSWORD=***
```

卡密校验服务的地址与凭据**不在源码里**，需要构建期注入（不加也能编过，只是校验会失败）：

```sh
  -PPSU_CARDKEY_BASE=https://你的域名 \
  -PPSU_CARDKEY_MASK=<8 字节十六进制掩码> \
  -PPSU_CARDKEY_KEY=<app_key 与掩码异或后的十六进制> \
  -PPSU_CARDKEY_SECRET=<app_secret 与掩码异或后的十六进制>
```

掩码算法：把明文字符串的每个字节与掩码循环异或，结果写成十六进制。运行时再异或回来。
（这样做的目的只是让 `strings` 搜不到明文，挡不住真正的逆向。）

### 2. 内核模块（8 个 KMI）

需要 Google DDK 的 kdir / src 预编译包，以及本机可用的 clang 交叉工具链：

```sh
make -C <ddk-kdir> M=<本仓库>/kernel \
  KSU_PACKAGE_NAME=com.evilsu \
  KSU_EXPECTED_SIZE=0x376 \
  KSU_EXPECTED_HASH=<签名证书 DER 的 SHA-256>
```

- 产物必须命名成 `<kmi>_kernelsu.ko`（下划线），`ksud` 靠 `strip_suffix("_kernelsu.ko")` 反推支持的 KMI。
- `KSU_EXPECTED_SIZE` / `KSU_EXPECTED_HASH` 是**管理器 APK 的 v2 签名证书**（DER 字节长度与其 SHA-256）。
  内核只有三项同时匹配（包名 + 长度 + 哈希）才会把该 APK 认作管理器。**换签名证书必须重编模块。**
- 版本号 = `30000 + git 提交数`。本仓库提交数为 2707，因此 versionCode 为 32707。

### 3. ksud 与 ksuinit

```sh
cargo build --package ksud   --target aarch64-linux-android --release
cargo build --package ksuinit --target aarch64-linux-android --release   # 需 +crt-static、16K page
```

`ksud` 产物放到 `manager/app/src/main/jniLibs/arm64-v8a/libksud.so` 后重编 APK，才会内嵌新的内核模块。

## 免责声明

本项目按许可证「原样」提供，**不提供任何担保**，使用风险自负。
修改系统内核与刷机可能导致设备无法启动，请自行备份。
`KernelSU` 的名称与商标不属于许可证授权范围，本仓库使用自有名称。
