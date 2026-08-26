# agent.md — AI 编码代理指南

本文件供 AI 编码代理在本仓库工作时阅读。请先通读本文再动手改代码。

## 项目简介

- **上游**：[zhanghai/MaterialFiles](https://github.com/zhanghai/MaterialFiles) —— 开源 Material Design 文件管理器，GPLv3。
- **本仓库**：`XuexGao/MaterialFiles-Wear`，上游的 fork，当前版本 `2.0.0-rc1 (260825)`。
- **定位**：一个 Android 文件管理器，后端基于 **Java NIO2 File API** 实现（非 `java.io.File`、非 `ls` 解析），通过 JNI 绑定 Linux syscall，支持软链、权限、SELinux 上下文；支持压缩包查看/解压/创建、FTP/SFTP/SMB/WebDAV、root、Shizuku。
- **⚠️ 本 fork 的目标**：**Wear OS 方屏手表适配**。已落地：全局界面缩放（见下）、文本/图片直开内置查看器、图片查看器手势改进；README 与关于页已注明 fork 说明。

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
- `navigation/` —— 抽屉导航：分区依次为 存储空间 → **工具**（含 FTP 服务器入口）→ 标准文件夹 → 书签文件夹 → 菜单；分区用 null 分隔、标题用 `NavigationTitleItem` + `NavigationTitleItemBinding`
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

- 核心实现：`me.zhanghai.android.files.ui.UiScaleHelper`。在 `AppActivity.attachBaseContext()` 中用 `createConfigurationContext` 调低 `densityDpi`，全 app 的 dp/sp 尺寸随之等比缩小；对话框/弹窗随宿主 Activity 自动缩放。
- **默认值**：`UiScalePreference.resolveDefaultScale()` 按屏幕最小边 dp ÷ 360dp（典型手机宽度）比例计算并吸附到 5% 步进（40–100%）；用户手动保存过的值优先（`currentEffectiveScale()` 读 `defaultSharedPreferences` 判断）。
- 设置页「界面 → 界面缩放」是自定义 `UiScalePreference` + `UiScalePreferenceDialogFragment`（SeekBar 40–100%，重置=屏幕推荐值，保存时才持久化）。持久化后经 `Settings.UI_SCALE` observer 直接调 `UiScaleHelper.sync()` 重建所有 Activity 生效。
- Debug 构建默认启用 `app/CrashLogger`：未捕获异常写入 `Android/data/<applicationId>/cache/logs/crash_*.txt`（保留最新 5 个），用于无 adb 设备（如手表）排查闪退。
- **新增 Activity 必须继承 `AppActivity`**，否则不会被缩放，且 `NightModeHelper` 会直接抛异常。
- 刻意保留 `screenWidthDp/screenHeightDp/smallestScreenWidthDp` 为未缩放值，保证布局限定符选择行为不变。
- Manifest 已声明 `<uses-feature android:name="android.hardware.type.watch" android:required="false" />`。

### 文件打开与查看器

- 点击文件的默认路径：`filelist/FileListFragment.openFileWithIntent()`。压缩包走 FileJobService；**文本与图片直接进内置查看器**（`TextEditorActivity` / `ImageViewerActivity`，通过 `setClass` 强制内部解析）；其他类型交给系统 VIEW intent（可能弹选择器）；「打开方式」菜单始终弹系统选择器。
- 文本类型判定：`MimeType.isText`（`file/MimeTypeTypeExtensions.kt`）。
- 图片查看器（`viewer/image/`）：ViewPager2 + PhotoView / SubsamplingScaleImageView。手势约定：
  - 相邻图片随手指连续移动（无 PageTransformer 动画）；
  - 点击任意位置（含图片外区域，经 `attacher.setOnOutsidePhotoTapListener`）切换标题栏显隐；
  - 放大后滑动先平移图片，到边缘后松手再次滑动才翻页 —— 实现方式是 Adapter 里 `installPanInterceptor` 在图片仍有平移余量时对 parent 调 `requestDisallowInterceptTouchEvent(true)`；**ViewPager2 是 final 类不能继承**；
  - PhotoView `minimumScale = 1f`，不允许缩小到初始全屏大小以下；双击后上下拖动为连续快速缩放（经 `attacher.setOnDoubleTapListener` 自实现，与 SSV quick scale 手感一致），单击切换标题栏。
  - ⚠️ ktor URL 的绝对路径以**前导空段**标记（`Url("/a").rawSegments == ["", "a"]`）；WebDavPath.url 必须补该空段，否则 dav4jvm 的成员关系逐段比较会把子目录内容全部判为无关项（表现为根目录可见、进去全空）。

### 版本号与包名

- 版本在 `app/build.gradle` 的 `versionCode` / `versionName`。
- `applicationId` 为 **`com.xuexgao.android.files`**（fork 改名），provider authority 由 `resValue` 从 applicationId 自动派生；**Kotlin/Java 代码包名仍是 `me.zhanghai.android.files`**（上游类名未改，AppUpgraders 里的序列化类名字符串依赖它，勿动）。

### 依赖的“坑”（改版本前先读注释）

`app/build.gradle` 中大量依赖带有原因注释，例如：

- SMBJ 锁定 `0.11.5`：0.12+ 破坏匿名认证；
- `mina-core` 锁定 `strictly 2.1.3`：更高版本不兼容 API < 24;
- BouncyCastle 用 `jdk15to18` 变体规避 Jetifier 问题；
- Guava/listenablefuture 冲突处理。

**升级依赖前必须读这些注释并验证约束仍然成立。**

### WebDAV 栈（dav4jvm 4.0.0 / Ktor）

- 已从 okhttp 版 dav4jvm fork 迁移到官方 **`com.github.bitfireAT:dav4jvm:4.0.0`**（Ktor 3.5 API），需自行声明 `io.ktor:ktor-client-auth` 与 `io.ktor:ktor-client-okhttp`（dav4jvm 里是 implementation 不导出）；保留 xpp3 排除、新增 guava 排除（用 app 的 android 变体）。
- 关键差异：包名 `at.bitfire.dav4jvm.ktor.*`；操作是 suspend/Flow，经 `DavResourceCompat.runBlockingIo {}` 桥接回阻塞 NIO（中断→InterruptedIOException）；`HttpClient` 按 Authority 缓存于 `Client.clients`；认证用 `PreemptiveBasicDigestAuthProvider`+`DomainAuthProvider` 与自定义 Bearer provider（都装在 OkHttp 引擎上，复用全局 OkHttpClient/cookie/超时）。
- 4.0.0 的具体异常构造器变 internal：NIO 映射靠 `DavException(msg, statusCode=…)` + `DavExceptionExtensions.kt` 的 statusCode 回退分支。
- 自定义 PATCH / range-PUT 需要的 `checkStatus`/重定向循环在库里是 internal，已在 `DavResourceCompat.kt` 本地移植（`checkStatusCompat`/`followRedirectsCompat`）。

### 生成物与辅助脚本

- `mime/` —— 从 shared-mime-info 生成的 MIME 扩展（`generate-code.sh`），不要手改生成文件。
- `utils/generate-custom-themes.sh` —— 生成自定义主题色资源；`import-translations.sh` —— 导入翻译。
- `signing.gradle` —— release 签名读取 `signing.properties`（见 `signing.properties.example`）或环境变量 `STORE_FILE` 等；无配置时会交互式询问。

## 其他注意

- 上游 README 明确：此应用**不是 DocumentsUI 的替代品**，依赖 DocumentsUI 授权外置 SD 卡；不要往这个方向改。
- 应用面向 Android 6.0+（minSdk 23），改动时注意 API 级别兼容性。
- 提交信息遵循上游风格：`类型: 描述`（如 `Fix: ...`、`Build: Update dependencies`、`Feat: ...`）。
- Git 远程为 fork `origin = XuexGao/MaterialFiles-Wear`；同步上游时可添加 upstream remote。
- CI 用 GitHub Actions（`.github/workflows/android.yml`，actions/checkout@v7、setup-java@v6、upload-artifact@v7），`gh workflow run android.yml --ref master` 手动触发，产物名 `app-debug.apk`。
