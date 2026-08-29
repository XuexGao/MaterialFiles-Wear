# MaterialFiles-Wear

[![Android CI](https://github.com/XuexGao/MaterialFiles-Wear/actions/workflows/android.yml/badge.svg)](https://github.com/XuexGao/MaterialFiles-Wear/actions/workflows/android.yml) [![GitHub release](https://img.shields.io/github/v/release/XuexGao/MaterialFiles-Wear)](https://github.com/XuexGao/MaterialFiles-Wear/releases) [![License](https://img.shields.io/github/license/XuexGao/MaterialFiles-Wear?color=blue)](LICENSE)

基于 [Material Files](https://github.com/zhanghai/MaterialFiles) 的开源 Material Design 文件管理器，针对 **Wear OS 手表与小屏设备** 适配。

需要 Android 6.0+（API 23）。

## 下载

从 [GitHub Releases](https://github.com/XuexGao/MaterialFiles-Wear/releases/latest) 下载签名的 APK（当前版本 [v2.0.0](https://github.com/XuexGao/MaterialFiles-Wear/releases/tag/v2.0.0)）。

## 相对上游的新增功能

- **多窗口**：主页左右滑动无缝切换多个目录窗口，支持「新建窗口」，各窗口独立记忆状态
- **内置视频播放器**：直接播放视频，支持捏合缩放、旋转、音量与进度控制
- **APK 提取**：查看已安装应用信息，一键提取安装包到下载目录
- **界面缩放**：按屏幕尺寸自动适配，可手动调节（40%–100%）
- **字体缩放**：独立于系统的字体大小设置（80%–130%）
- **弹窗全屏**：小屏幕上弹窗全屏显示，手表默认开启
- **内置查看器直开**：文本、图片、视频等支持的文件直接打开，不再弹选择器
- **图片查看器增强**：双击/捏合缩放、无缝翻页、缩放范围限制
- **Material Design 3 Expressive** 界面（含动态取色），启动画面/图标适配手表
- **搜索与退出**：主页工具栏直达

## 预览

<p><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="32%" /> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width="32%" /> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width="32%" />
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" width="32%" /> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5.png" width="32%" /> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/6.png" width="32%" /></p>

## 上游特性

本应用保留了上游 [Material Files](https://github.com/zhanghai/MaterialFiles) 的全部能力：面包屑导航、Root 支持、压缩文件查看/提取/创建、FTP/SFTP/SMB/WebDAV、可定制主题与纯黑夜间模式，以及 Linux 友好（符号链接、文件权限、SELinux 上下文）等，详见[上游 README](https://github.com/zhanghai/MaterialFiles#material-files)。

## 致谢与许可

本项目基于 [zhanghai/MaterialFiles](https://github.com/zhanghai/MaterialFiles)，感谢原作者 [Hai Zhang](https://github.com/zhanghai)。

    Copyright (C) 2018 Hai Zhang

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
