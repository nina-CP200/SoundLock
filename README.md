<div align="center">

# 声锁

**让手机在需要安静的地方，保持安静。**

默认锁住媒体外放，避免视频、游戏或社交应用突然从扬声器出声。需要外放时，按音量加键并明确确认。

[下载最新版本](https://github.com/nina-CP200/SoundLock/releases/latest) · [安装说明](docs/INSTALL.md) · [反馈问题](https://github.com/nina-CP200/SoundLock/issues)

[![Android 14+](https://img.shields.io/badge/Android-14%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/about/versions/14)
[![Release](https://img.shields.io/github/v/release/nina-CP200/SoundLock?label=release&color=006970)](https://github.com/nina-CP200/SoundLock/releases/latest)
[![License](https://img.shields.io/badge/license-MIT-006970)](LICENSE)

</div>

## 它做什么

- **默认锁住外放**：媒体音量归零，解锁前不会直接播放。
- **一次确认**：按音量加键，顶部出现提示，点击「确认解锁」才恢复最低一格音量。
- **耳机不受影响**：外接音频正常使用，切回扬声器后自动上锁。
- **自动回到保护**：15 分钟没有媒体播放、耳机断开或服务重连后自动上锁。
- **随手可锁**：通知栏和系统快捷设置提供立即上锁入口。

## 开始使用

1. 从 [Releases](https://github.com/nina-CP200/SoundLock/releases/latest) 下载 APK 并安装。
2. 打开声锁，按引导开启「声锁 · 音量保护」无障碍服务。
3. 需要外放时按音量加键，再点击顶部提示中的「确认解锁」。

更完整的权限与验证步骤见 [安装说明](docs/INSTALL.md)。

## 适配与边界

支持 Android 14 及以上，主要适配 OPPO / ColorOS，其他机型仍待验证。若手机已安装旧调试版，安装正式版前通常需要先卸载，原有设置会清除。

声锁只处理媒体音量，不控制通话、铃声和闹钟；不读取屏幕内容、不录音、不申请网络权限。

**普通应用无法保证绝对零漏音。** 系统或其他应用修改音量后，声锁只能尽快归零；服务被停止时也无法继续保护。重要场合请先在自己的设备上验证。

## 反馈

遇到问题请到 [Issues](https://github.com/nina-CP200/SoundLock/issues) 反馈，并注明机型、系统版本、声锁版本和复现步骤。提交前请删除日志与截图中的个人信息。

## 开发

<details>
<summary>构建、测试与签名</summary>

需要 JDK 17 或 21、Android SDK Platform 35：

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug
```

正式包需要签名配置，默认读取 `~/.android/soundlock-signing.properties`，也可用环境变量 `SOUNDLOCK_SIGNING_PROPERTIES` 指定位置：

```sh
./gradlew :app:assembleRelease
```

仓库不存放 APK、签名密钥或密码。欢迎提交 Pull Request。

</details>

## 许可证

[MIT](LICENSE)
