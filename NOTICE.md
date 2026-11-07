# NOTICE — 第三方组件与许可证

本发行版（sad / 今日头条）是 **KernelSU 的修改版**，按各自上游许可证分发。

## 主体

| 组件 | 许可证 | 版权 |
|---|---|---|
| KernelSU（管理器、ksud、ksuinit） | **GPL-3.0** | Copyright (C) tiann and KernelSU contributors |
| KernelSU 内核模块（`kernel/`、`uapi/`） | **GPL-2.0** | 同上（`kernel/LICENSE`，模块声明 `MODULE_LICENSE("GPL")`） |

两份许可证全文随源码包提供：`LICENSE`（GPL-3.0）、`kernel/LICENSE`（GPL-2.0）。

## 管理器内置的第三方库

| 组件 | 许可证 |
|---|---|
| AndroidX / Jetpack Compose / Material3 | Apache License 2.0 |
| OkHttp (Square) | Apache License 2.0 |
| Kotlin 标准库 / kotlinx.coroutines | Apache License 2.0 |
| miuix-kmp (top.yukonga.miuix) | Apache License 2.0 |
| Protocol Buffers (Google) | BSD-3-Clause |
| 其余依赖 | 见 `manager/gradle/libs.versions.toml`，各组件许可证可在其官方仓库获取 |

Apache-2.0 全文：<https://www.apache.org/licenses/LICENSE-2.0>

## 说明

- 本项目未修改上述第三方库的许可证声明，也未移除其版权与许可信息。
- Apache-2.0 组件按「原样」提供，不提供担保。
- 上游 KernelSU 的品牌名与商标不属于 GPL 授权范围，本发行版已改用自有名称与图标，不代表上游官方发布。
