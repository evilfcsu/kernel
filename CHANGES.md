# MODIFICATIONS — 对上游 KernelSU 的改动说明

依据 GPL-3.0 第 5(a) 条：修改版必须显著声明「曾经修改」并给出日期。本文件列出全部改动。

- **上游项目**：tiann/KernelSU（<https://github.com/tiann/KernelSU>）
- **上游版本**：`730f6446`（上游最新，本地仓库 2707 提交）
- **修改日期**：2026-11-01 至 2026-11-09
- **本发行版**：Evil（包名 `com.evilsu`）

## 改动清单

1. **品牌化**
   - `applicationId` 改为 `com.evilsu`，应用名改为 `Evil`
   - 替换启动图标（`res/mipmap-*/brand_fg.png` + `res/mipmap-anydpi/ic_launcher.xml`，背景色 `#020202`）
   - 首页顶部标题改为自有文案（`res/values/strings.xml` 的 `home_brand_title`）
   - 首页顶部标题为 `Evil`，无副标题；隐藏开启时额外显示「结束进程并隐藏」按钮
2. **构建适配（不改逻辑）**
   - `manager/app/build.gradle.kts`：删除 `externalNativeBuild` / CMake / `ndkVersion`，`prefab = false`，native 库改用预编译 `src/main/jniLibs/arm64-v8a/`
   - `manager/build.gradle.kts`：版本号支持 `-PPSU_VERSION_CODE` / `-PPSU_VERSION_NAME` 注入，并对 git 取值为空时兜底
   - `kernel/Kbuild`：追加 `ccflags-y += -fno-stack-protector`（外部模块构建不经过 `make prepare`，缺少 per-task canary 参数会引用 GKI 内核不存在的 `__stack_chk_guard`）
3. **行为改动**
   - 关闭上游更新检查：`ui/util/Downloader.kt` 的 `checkNewVersion()` 直接返回空结果（原实现请求 `tiann/KernelSU` 的 GitHub release，换皮后会引导用户去装上游版本）
4. **自研功能**
   - 「隐藏与呼出」（stealth）：`stealth/`（`Stealth` / `BootReceiver` / `StealthSettingsActivity`）、`assets/stealth/watch.sh`
   - 隐藏采用 `pm uninstall`（真卸载），配置镜像到 `/data/adb/evil/stealth.conf`；呼出为目标应用内的音量键手势，触发后装回并启动、监听自动退出
   - 手动安装时 2 秒轮询发现「从无到有」→ 恢复显示并清数据
   - 私有目录 `/data/adb/evil`，开机钩子 `/data/adb/service.d/evil-watch.sh`
5. **卡密闸门（本版保留）**
   - `ui/screen/cardkey/`（`CardKeyApi` / `CardKeyStore` / `CardKeyScreen`）+ `MainActivity` 在 `KernelSUTheme` 内包 `CardKeyGate`，启动图放行接到闸门
   - 联网校验：服务地址与 app_key/app_secret **不写入源码**，由构建期 `-PPSU_CARDKEY_*` 注入（`BuildConfig`），源码内既无域名也无可逆掩码常量
   - 说明：源码即含闸门，任何人可自行编译去掉闸门后的版本（GPL-3.0 允许）
6. **内核模块与用户态二进制**
   - `kernel/`、`uapi/` 按上述改动重编为 8 个 KMI 的 `kernelsu.ko`（构建期注入包名与签名证书指纹）
   - `userspace/ksud`、`userspace/ksuinit` 随源码重编，8 个 `.ko` 与 `ksuinit` 内嵌进 `libksud.so`

## 声明

本发行版按 **GPL-3.0**（管理器）与 **GPL-2.0**（内核模块）分发，**不提供任何担保**。
完整对应源码见同目录的 `KernelSU-331-src.tar.gz`。第三方组件许可见 `NOTICE.md`。
