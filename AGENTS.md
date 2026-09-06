# blbl-D 构建与签名规范

## 编译环境

- 使用 Java 17。macOS 默认可设置：
  `export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home`
- Android SDK 使用本机实际安装目录；当前机器为：
  `export ANDROID_HOME=/Users/ddd/Library/Android/sdk`
  同时设置 `ANDROID_SDK_ROOT=$ANDROID_HOME`。
- 不使用 Java 26 编译；当前 Kotlin/Gradle 组合不兼容该版本。
- `gradlew` 没有执行权限时使用 `sh gradlew ...`，不为临时编译修改文件权限。

## 构建命令

### Release（固定签名，默认交付版本）

使用仓库脚本加载本地签名配置：

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
export ANDROID_HOME=/Users/ddd/Library/Android/sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
bash keystore/build-release.sh -PversionName=<版本号> -PversionCode=<版本号>
```

脚本通过 `keystore/local-signing.gradle` 读取 `keystore/signing.json`，并使用 `keystore/release.keystore`。签名密码、别名和密钥只保存在本机文件或环境变量中，不写入源码、日志、提交信息或文档。

Release APK 输出路径：
`app/build/outputs/apk/release/blbl-D-<versionName>-release.apk`

### Debug

Debug 变体也绑定 release 签名配置；直接执行 `assembleDebug` 前必须提供完整签名参数，否则会在 `packageDebug` 阶段失败。需要固定签名交付时优先使用上面的 Release 脚本。

## 安装到 Android 设备

先列出设备并使用明确序列号，禁止在多设备连接时省略 `-s`：

```sh
$ANDROID_HOME/platform-tools/adb devices
$ANDROID_HOME/platform-tools/adb -s <设备序列号> install -r app/build/outputs/apk/release/blbl-D-<versionName>-release.apk
$ANDROID_HOME/platform-tools/adb -s <设备序列号> shell am start -n blbl.r19988088/blbl.cat3399.ui.MainLauncherDarkAlias
```

小米盒子常用网络序列号格式为 `<局域网IP>:5555`。`install` 返回 `Success` 只证明安装成功；启动、界面、遥控器操作和视觉效果需要单独验证。

## 验证要求

- 只有看到 `BUILD SUCCESSFUL` 才报告构建成功。
- 编译失败时区分 Java/SDK 环境错误、签名配置错误和源码编译错误，不把 APK 安装结果当作编译证据。
- 修改首页列表、封面或切换逻辑后，至少验证首页进入、Tab 切换、刷新、滚动、焦点和封面显示；视觉或手感变化需单独记录。
