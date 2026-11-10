---
id: soundlock-architecture
type: architecture-design
status: draft
title: 声锁 · 架构与不变量
parent: soundlock-goal
---

# 声锁 · 架构与不变量

单进程、单模块的 Android 应用。保护的全部行为由一个常驻的无障碍服务驱动，服务周边是四个可以脱离 Android 单独推理的纯策略类，以及两个适配层（音量通道、提示浮层）。

## 组件

- **保护服务 `GuardService`**（无障碍服务）：唯一持有策略实例的地方。汇总所有事件源，推进状态、写音量、发布快照、维护定时器与常驻通知。
- **纯策略核心 `LockPolicy` / `VolumeKeyGate` / `MuteRefresh` / `VolumeScale`**：上锁与授权状态机、音量键决策、静音重试节流、音量档位换算。只依赖 Kotlin 标准库，靠 JVM 单测覆盖。
- **音量通道 `MediaVolumeControl`**：读写媒体音量，并封装 OPlus 的无障碍音量兼容通道。
- **提示浮层 `GuardCapsule`**：`TYPE_ACCESSIBILITY_OVERLAY` 自绘的顶部胶囊，承载确认与反馈。
- **状态与入口 `GuardState` / `MainActivity` / `LockTileService` / `LockReceiver`**：进程内快照与 `armed` 持久化、Compose 界面、快捷设置磁贴、通知动作与定时广播。

## 依赖边

```
MainActivity ─┐
LockTileService ├─→ GuardState ─→ GuardService
LockReceiver ─┘                        │
                                       ├─→ LockPolicy / VolumeKeyGate / MuteRefresh
                                       ├─→ MediaVolumeControl (+ VolumeScale)
                                       ├─→ GuardCapsule
                                       └─→ GuardState.publish

纯策略核心 → 无 Android 依赖
```

界面与入口只读快照、只调用服务的少数公开方法（`setProtection` / `lockNow` / `confirmUnlock` / `checkTimer` / `refreshSettings`），从不自己碰音频。

## 不变量

1. **纯逻辑不进 Android**：策略、门控、节流、换算这四个类不引用任何 `android.*` 类型，因此能在没有设备的情况下用 JUnit 验证；Android 类型只允许出现在服务、通道、浮层与界面里。
2. **音量键回调里只做内存决策**：`onKeyEvent` 有系统时限，路由查询、音量写入、浮层渲染一律延后到主线程的下一轮任务；按键的消费决定必须先返回。
3. **Fail closed**：默认 `armed = true`、`unlocked = false`、`route = UNKNOWN`，也就是默认需要静音。读取音频状态抛异常时置 `UNKNOWN`、上锁、尽力归零，并把原因显示给用户，而不是跳过保护。
4. **授权是最易失的状态**：输出路径任何变化（包括经过 `UNKNOWN`）、媒体音量从正数回到零、空闲超时、暂停保护、服务重连都会撤销解锁；撤销后重新回到"按音量加键才确认"的状态。
5. **只写媒体流**：音频写入只针对 `STREAM_MUSIC`（OPlus 兼容通道下附带写入别名的 `STREAM_ACCESSIBILITY`），不改通话、铃声、闹钟三路流。
6. **不认识就保护**：只有白名单内的外接设备类型（有线/蓝牙/USB/HDMI/助听器/底座等）才算外接并绕过上锁；其余一律当作可能外放处理。
7. **单一状态源**：首页、常驻通知、磁贴、使用说明都从同一份 `GuardSnapshot` 读状态，不允许各自维护一份。
8. **状态只携带数据，不携带显示文本**：枚举与数值在界面与通知里各自翻译，已翻译的字符串不得从音频层或状态层流出来。
9. **放行只在有证据的当下成立**：白名单要「前台包名属于白名单」与「确实有媒体在播」同时成立；两者任一无法确认就按未命中处理。

## 决策

- **用无障碍服务承载保护，而不是前台服务 + 悬浮窗。** 只有无障碍服务能拦截音量键（`flagRequestFilterKeyEvents`），`TYPE_ACCESSIBILITY_OVERLAY` 又免去「显示在其他应用上层」权限；系统也更愿意长期保留无障碍服务，这本身就是保护能持续的前提（保活这一点由作者确认）。
- **提示由声锁自绘。** ColorOS 流体云尚未接入，顶部胶囊只是声锁自己的窗口，因此不接管也不遮挡系统音量面板。
- **轮询 + 设置观察者双保险。** 部分厂商的音量面板改动不会触发 `Settings.System` 的 `ContentObserver`，所以除了观察者还有一轮兜底轮询：保护启用且（屏幕亮或正在播放）时 500ms，其余 2s。
- **空闲计时从「播放停止」起算，而不是从解锁起算。** 解锁后只要仍在播放就不计时，符合「一次授权、一次使用」；一次解锁不会因为长时间播放而中途失效。
- **自动上锁用系统闹钟兜底。** 有精确闹钟权限时用 `setExactAndAllowWhileIdle`，否则退化为 `setAndAllowWhileIdle`，息屏深度休眠中的上锁可能因此推迟——这是不额外索取权限的代价。
- **兼容通道只对 OPlus 开启，且绝不碰读屏通道。** OPlus 对后台应用的媒体流写入有限制，才需要走与媒体联动的无障碍音量；一旦检测到已启用带独立音量的无障碍服务，就退回标准媒体接口，避免改写读屏用户的音量。

## 边界

- 只在 OPPO / ColorOS 上实测；其他厂商走标准媒体接口，未验证。OPPO 原生流体云未接入。
- 服务被系统停止、应用被强制清理或设备重启后服务未自动恢复期间，声锁不能保护任何人；应用内说明如实写出这一点。
- 系统或其他应用改音量后只能尽快归零，无法阻止写入发生，因此不承诺绝对零漏音。
