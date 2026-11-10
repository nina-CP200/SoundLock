---
id: soundlock-app
type: module-design
title: app 模块（Android 装配与发布）
status: draft
parent: soundlock-architecture
---

# app 模块（Android 装配与发布）

这个模块负责把 `dev.soundlock` 的保护逻辑装配成一个能安装、能签名的 Android 应用：构建配置、清单里暴露的系统组件、申请的权限、资源与发布方式。保护逻辑本身不在这一层，见 `soundlock-package`。

## 装配

- 单模块工程（`:app`），Kotlin + AGP + Compose 版本由根 `build.gradle.kts` 统一锁定；运行时依赖只有 Compose 与 `androidx.activity`，测试只有 JUnit4——新增依赖前先确认它是否值得破坏这份克制。
- `compileSdk`/`targetSdk` 35、`minSdk` 34：Android 14 是产品承诺的支持下限（README 与安装文档都这么写），更低的机型不在支持范围。
- 应用标识 `dev.soundlock`，`allowBackup=false`，并且 `data_extraction_rules` 把云备份与设备迁移的全部域排除：保护开关属于设备本地状态，不跟着账号走。

## 清单与权限

暴露给系统的四个组件及谁能调起它们：

- `MainActivity`：LAUNCHER 入口，`singleTop`。
- `GuardService`：由 `BIND_ACCESSIBILITY_SERVICE` 保护的无障碍服务。配置里 `canRetrieveWindowContent=false`（不读屏幕）、`isAccessibilityTool=false`、只收 `typeWindowStateChanged` 并请求过滤按键事件——这份配置就是产品对用户的隐私承诺，改动需要同步无障碍说明文案。
- `LockTileService`：由 `BIND_QUICK_SETTINGS_TILE` 保护的快捷设置磁贴。
- `LockReceiver`：`exported=false`，只处理 `LOCK` / `CONFIRM` / `TIMER` 三个内部动作。

权限只有三个：`MODIFY_AUDIO_SETTINGS`（写媒体音量）、`POST_NOTIFICATIONS`（常驻状态通知）、`SCHEDULE_EXACT_ALARM`（自动上锁定时）。没有 `INTERNET`、没有麦克风、没有 `SYSTEM_ALERT_WINDOW`。

## 资源与文案

`strings.xml` 里只有应用名、无障碍服务说明、作者寄语；其余界面文案是硬编码中文，界面未做本地化。无障碍说明是用户授权前唯一读到的权利说明，改权限或改行为时必须同改。

## 签名与发布

- release 签名配置从 `~/.android/soundlock-signing.properties` 或 `SOUNDLOCK_SIGNING_PROPERTIES` 指向的文件读取；文件不存在时照常构建未签名 release 包，不阻断调试。
- 仓库不存放 APK、密钥与口令。
- 调试包与正式包签名不同，换装通常需要先卸载；卸载会清掉本地保护设置，需要重新开启服务与授权。安装文档写明了这一点。
