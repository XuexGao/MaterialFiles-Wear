# agent.md — AI 编码代理指南

本文件供 AI 编码代理在本仓库工作时阅读。请先通读本文再动手改代码。

## 项目简介

- **上游**：[zhanghai/MaterialFiles](https://github.com/zhanghai/MaterialFiles) —— 开源 Material Design 文件管理器，GPLv3。
- **本仓库**：`XuexGao/MaterialFiles-Wear`，上游的 fork。
- **定位**：一个 Android 文件管理器，后端基于 **Java NIO2 File API** 实现（非 `java.io.File`、非 `ls` 解析），通过 JNI 绑定 Linux syscall，支持软链、权限、SELinux 上下文；支持压缩包查看/解压/创建、FTP/SFTP/SMB/WebDAV、root、Shizuku。
- **⚠️ 本 fork 的目标**：仓库名中的 "-Wear" 表明目标是 **Wear OS 移植**，但目前代码中**尚无任何 Wear 相关内容**（无 wear 模块、无圆形屏幕适配）。master 与上游一致。

## 构建与环境要求

```bash
./gradlew assembleDebug          # 构建 debug APK
./gradlew lintVitalRelease       # release lint 检查（CI 会跑这两个）
```

| 项 | 值 |
|---|---|
| JDK | 21（CI 用 temurin 21） |
| Gradle | wrapper 9.3.1（用 `./gradlew`，不要用系统 gradle） |
| AGP | 9.1.0 |
| Kotlin | 2.3.20 |
| Android SDK | compileSdk 36 / minSdk 23 / targetSdk 34，buildTools 37.0.0 |
| NDK / CMake | ndkVersion 28.1.13356709；CMake 构建 2 个原生库 |

- 原生代码只有两个小库：`app/src/main/jni/syscall.c`（Linux syscall 绑定）和 `hiddenapi.c`，改动需谨慎且通常无需触碰。
- 首次构建需要 Android SDK licenses 已接受、SDK Platform 36 与上述 NDK 版本已安装。

## 模块与代码结构

单模块项目：`settings.gradle` 只 include `:app`。

主包 `app/src/main/java/me/zhanghai/android/files/`：

- `provider/` —— **核心**：各文件系统的 NIO2 FileSystemProvider 实现
  - `linux/`（syscall 直连本地文件系统）、`archive/`、`document/`(SAF)、`content/`、`ftp/`、`sftp/`、`smb/`、`webdav/`、`root/`、`remote/`、`common/`
- `file/` —— 共享抽象：FileItem、MimeType、各类 Uri 封装等
- `filelist/` / `filejob/` / `fileaction/` —— 文件列表 UI、后台作业（复制/移动/压缩等）、操作逻辑
- `ui/` / `navigation/` / `viewer/` —— 通用 UI、面包屑导航、文本/图片查看器
- `storage/` —— 存储位置管理（文档树、外部存储、各类服务器书签的编辑/列表）
- `settings/`、`theme/`、`about/`、`app/`（Application/Activity 入口与初始化）
- `ftpserver/`（内置 FTP 服务器）、`terminal/`、`colorpicker/`、`coil/`（图片加载定制）、`hiddenapi/`、`nonfree/`（Firebase Crashlytics 初始化）

UI 技术栈：**View 体系 + ViewModel/LiveData**，ViewBinding 开启，**没有 Compose**。协程可用。`coreLibraryDesugaring` 已开启（Java 8 语法 + 新 API 脱糖）。

## 特殊机制与约定

### `//#ifdef NONFREE` 条件块

`app/build.gradle`、`AppInitializers.kt`、`AboutFragment.kt` 中有 `//#ifdef NONFREE ... //#endif` 注释块，包含 Firebase Analytics/Crashlytics 与 google-services 插件相关代码：

- **当前仓库按原样构建时这些代码是生效的**（`app/google-services.json` 已存在）。
- F-Droid 等免费构建由**外部脚本 sed 删除这些块**实现，仓库内没有预处理器。改动这些区域时保持标记完整。

### 界面缩放（方屏手表适配）

- 核心实现：`me.zhanghai.android.files.ui.UiScaleHelper`。在 `AppActivity.attachBaseContext()` 中用 `createConfigurationContext` 调低 `densityDpi`（默认 60%，见 `Settings.UI_SCALE`），全 app 的 dp/sp 尺寸随之等比缩小；对话框/弹窗随宿主 Activity 自动缩放。
- **新增 Activity 必须继承 `AppActivity`**，否则不会被缩放，且 `NightModeHelper` 会直接抛异常。
- 设置页「界面 → 界面缩放」为 SeekBarPreference（40–100%）；改动经 500ms 防抖后调用 `UiScaleHelper.sync()` 重建所有 Activity 生效。
- 刻意保留 `screenWidthDp/screenHeightDp/smallestScreenWidthDp` 为未缩放值，保证布局限定符选择行为不变。
- Manifest 已声明 `<uses-feature android:name="android.hardware.type.watch" android:required="false" />`。

### 版本号与包名

- 版本在 `app/build.gradle` 的 `versionCode` / `versionName`。
- 包名 `me.zhanghai.android.files`，同时用于 `applicationId` 和多个 provider authority（`resValue` 处自动派生）。若做 fork 改名，必须同步这些 authority。

### 依赖的“坑”（改版本前先读注释）

`app/build.gradle` 中大量依赖带有原因注释，例如：

- SMBJ 锁定 `0.11.5`：0.12+ 破坏匿名认证；
- `mina-core` 锁定 `strictly 2.1.3`：更高版本不兼容 API < 24;
- BouncyCastle 用 `jdk15to18` 变体规避 Jetifier 问题；
- Guava/listenablefuture 冲突处理。

**升级依赖前必须读这些注释并验证约束仍然成立。**

### 生成物与辅助脚本

- `mime/` —— 从 shared-mime-info 生成的 MIME 扩展（`generate-code.sh`），不要手改生成文件。
- `utils/generate-custom-themes.sh` —— 生成自定义主题色资源；`import-translations.sh` —— 导入翻译。
- `signing.gradle` —— release 签名读取 `signing.properties`（见 `signing.properties.example`）或环境变量 `STORE_FILE` 等；无配置时会交互式询问。

## 其他注意

- 上游 README 明确：此应用**不是 DocumentsUI 的替代品**，依赖 DocumentsUI 授权外置 SD 卡；不要往这个方向改。
- 应用面向 Android 5.0+（现 minSdk 23），改动时注意 API 级别兼容性。
- 提交信息遵循上游风格：`类型: 描述`（如 `Fix: ...`、`Build: Update dependencies`、`Feat: ...`）。
- Git 远程为 fork `origin = XuexGao/MaterialFiles-Wear`；同步上游时可添加 upstream remote。
