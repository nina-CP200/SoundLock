# 开发与发布

## 环境

需要 JDK 17 或 21、Android SDK Platform 35，以及 SDK Build Tools 35.0.0。Android Studio 可安装这些组件，命令行也可执行：

```sh
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```

使用 Android Studio 打开仓库根目录，或克隆后在根目录运行 Gradle Wrapper。通过 `ANDROID_HOME` 指向自己的 SDK；也可在不提交到 Git 的 `local.properties` 中配置 `sdk.dir`。无需安装系统 Gradle。

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

调试包位于 `app/build/outputs/apk/debug/app-debug.apk`。Windows 使用 `gradlew.bat`。单元测试报告位于 `app/build/reports/tests/testDebugUnitTest/index.html`，Lint 报告位于 `app/build/reports/lint-results-debug.html`。

## 正式签名

正式签名由本机管理。复制仓库中的占位示例并填写自己的配置：

```sh
mkdir -p "$HOME/.android"
cp docs/signing.properties.example "$HOME/.android/soundlock-signing.properties"
```

将 `storeFile` 改成密钥库的绝对路径，填写密码和别名。示例中的密码都是占位符。需要其他配置位置时，设置环境变量 `SOUNDLOCK_SIGNING_PROPERTIES`。

如果是在维护自己的分支，可用 `keytool` 交互创建密钥；原项目后续版本应继续使用原有发布密钥：

```sh
keytool -genkeypair -keystore "$HOME/.android/soundlock-release.jks" -alias soundlock -keyalg RSA -keysize 4096 -validity 10000
./gradlew :app:assembleRelease
```

配置正式签名后，安装包位于 `app/build/outputs/apk/release/app-release.apk`。没有签名配置时，Gradle 生成 `app-release-unsigned.apk`，用于检查 Release 构建，不能直接作为正式安装包发布。

签名密钥、密码及真实配置不能提交。密钥应妥善备份；丢失原密钥后无法继续覆盖升级原安装。自行签名的分支也无法覆盖原项目的正式版。

## 自动检查

`.github/workflows/android.yml` 在推送到 `main`、发起 Pull Request 或手动触发时，运行单元测试、Lint、Debug 和未签名 Release 构建。工作流仅需要读取仓库，不使用发布密钥，也不自动发布 APK。Action 依赖固定到提交 SHA。

## 发布新版本

1. 同步更新 `app/build.gradle.kts` 的 `versionName` 和递增的 `versionCode`，在 `CHANGELOG.md` 记录本版变化。
2. 运行上述测试与 Lint，并使用原发布密钥构建 Release。
3. 用 SDK Build Tools 的 `apksigner verify --verbose --print-certs` 验证 APK；证书 SHA-256 应与原正式版一致。
4. 将安装包命名为 `SoundLock-版本号-release.apk`，计算 SHA-256（macOS 用 `shasum -a 256`，Linux 用 `sha256sum`）。
5. 发布对应源码提交的版本标签与 GitHub Release，附 APK、更新说明、哈希和适配范围，并更新更新记录中的发布状态。

已有真机测试范围见 [安装说明](INSTALL.md)。发生新的设备兼容问题时，在 Issue 中补充机型、系统、应用版本与复现步骤即可。
