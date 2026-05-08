# blbl-android

本仓库只是使用 [cat3399/blbl](https://github.com/cat3399/blbl) 的简单修改，可能不稳定，不建议使用和对比。

## 免责声明

- 不得利用本项目进行任何非法活动。
- 不得干扰 B 站的正常运营。
- 不得传播恶意软件或病毒。
- 此外，为降低法律风险：
- 🚫 禁止在官方平台（B站）及官方账号区域（如 B 站微博评论区）宣传本项目。
- 🚫 禁止在微信公众号平台宣传本项目。
- 🚫 禁止利用本项目牟利，本项目无任何盈利行为，第三方盈利与本项目无关。
## 内部技术文档

### 检测更新

- 应用内检测更新固定读取 `R19988088/blbl-D` 的 GitHub Release，不再读取上游 `cat3399/blbl`。
- 入口代码：`app/src/main/java/blbl/cat3399/core/update/ApkUpdater.kt`。
- 最新版本接口：`https://api.github.com/repos/R19988088/blbl-D/releases/latest`。
- APK 下载地址由 Release 资源名决定：`blbl-D-latest-debug.apk`、`blbl-D-latest-release.apk`，以及按版本下载的 `blbl-D-{version}-{debug|release}.apk`。
- 如果检测更新提示 404，优先检查 `R19988088/blbl-D/releases` 是否存在 Release、对应 APK 资源是否已上传，以及 Release 是否为公开可访问。
