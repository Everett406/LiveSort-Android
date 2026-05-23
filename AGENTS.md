# LiveSort 开发规范

## 项目架构

本项目是 **Kotlin Multiplatform (KMP)** 项目，包含以下模块：

- `shared`: 共享业务逻辑模块（排序算法、数据模型、音频分析接口）
- `androidApp`: Android 应用（Jetpack Compose）
- `desktopApp`: Desktop 应用（Compose for Desktop）

## 技术栈

- **语言**: Kotlin 1.9.22
- **UI 框架**: Jetpack Compose / Compose Multiplatform 1.5.12
- **音频分析**: TarsosDSP 2.4
- **构建工具**: Gradle 8.5
- **最低 SDK**: Android API 26 (Android 8.0)
- **目标 SDK**: Android API 34
- **JDK**: 17

## 目录结构规范

```
shared/src/commonMain/kotlin/com/livesort/shared/
  ├── model/          # 数据模型（纯数据类）
  ├── sorting/        # 排序算法与情绪曲线
  ├── audio/          # 音频分析接口（expect）
  └── viewmodel/      # 共享 ViewModel（纯 Kotlin）

shared/src/androidMain/kotlin/com/livesort/shared/
  └── audio/          # Android 音频分析实现（actual）

shared/src/desktopMain/kotlin/com/livesort/shared/
  └── audio/          # Desktop 音频分析实现（actual）

androidApp/src/androidMain/kotlin/com/livesort/android/
  ├── MainActivity.kt
  ├── LiveSortApplication.kt
  └── ui/             # Android 专属 UI

desktopApp/src/desktopMain/kotlin/com/livesort/desktop/
  └── Main.kt         # Desktop 入口
```

## 开发规则

1. **业务逻辑必须放在 `shared` 模块的 `commonMain` 中**，不得写在平台专属代码里
2. **音频分析**使用 `expect/actual` 模式：`shared` 定义接口，各平台实现底层解码
3. **UI 代码**允许在各平台模块中独立实现，因为交互范式不同（触摸 vs 鼠标）
4. **字符串资源**: Android 用 `res/values/strings.xml`，Desktop 硬编码或使用 Compose resources
5. **协程**: 共享 ViewModel 使用 `kotlinx.coroutines`，Android 用 `Dispatchers.Main.immediate`

## 构建命令

```bash
# Android Debug APK
./gradlew :androidApp:assembleDebug

# Android Release APK
./gradlew :androidApp:assembleRelease

# Desktop 运行
./gradlew :desktopApp:run

# Desktop 打包 (Windows MSI/EXE)
./gradlew :desktopApp:packageMsi :desktopApp:packageExe

# Desktop 打包 (macOS DMG)
./gradlew :desktopApp:packageDmg

# Desktop 打包 (Linux DEB)
./gradlew :desktopApp:packageDeb
```

## GitHub Actions

所有构建均在 CI 中完成：
- `.github/workflows/build-android.yml` → 产出 Debug/Release APK
- `.github/workflows/build-desktop.yml` → 产出 Windows/macOS/Linux 安装包

本地只需修改代码并 push，无需安装 Android SDK 或打包工具。

## 与原项目的关系

- `LiveSort/` 目录是原始网页项目的克隆（只读参考）
- 本项目的核心算法（排序、情绪曲线）已从原项目的 Python/JS 移植到 Kotlin
- 原项目继续保留作为算法参考和对比基准
