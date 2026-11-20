# 简听（Jianting）

一个简洁的 Android 本地音乐播放器，支持本地曲库扫描、歌单管理、歌词页、主题切换，以及酷我 / 网易云 / 酷狗在线搜索与登录。

## 功能

- 本地音乐扫描：按歌曲 / 专辑 / 歌手 / 文件夹分类
- 歌单：新建、拖动排序、长按添加或移除
- 播放：后台播放、通知栏控制、进度拖拽、睡眠定时
- 歌词：逐行滚动、颜色与字号可调
- 外观：多套主题、封面背景模糊、均衡器
- 在线：三个内置搜索源，可单独开关；支持登录与 lx 脚本音源

## 构建

环境要求：

- JDK 17 或更高
- Android SDK 36（compileSdk 36，minSdk 24）

```powershell
.\gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

跑单元测试：

```powershell
.\gradlew.bat testDebugUnitTest
```

## 说明

- 在线搜索与播放依赖第三方平台公开接口，接口可能随时变化；本项目与其没有任何关联。
- 请仅将在线功能用于个人学习与测试，音乐版权归各平台及权利人所有。
- 仓库不包含签名文件、账号 Cookie 或任何密钥。
