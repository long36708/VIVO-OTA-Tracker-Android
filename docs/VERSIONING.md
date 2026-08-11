# 版本号体系（Versioning）

本文档说明 VIVO-OTA-Tracker-Android 项目的版本号如何配置、流转，以及如何在应用内显示。

## 1. 版本名（VERSION_NAME）的唯一定义处

版本名集中定义在 `buildSrc/src/main/kotlin/ProjectConfig.kt`：

```kotlin
object ProjectConfig {
    const val APP_NAME = "vivo OTA Tracker"
    const val VERSION_NAME = "1.3.1"   // ← 版本名只改这一行
    // ...
}
```

`ProjectConfig` 是 `buildSrc` 模块里的 Kotlin 顶层 `object`（无 package 声明）：
- 对所有模块的 **构建脚本（`.gradle.kts`）** 可见；
- **不能直接被应用源码（Kotlin 业务代码）引用**。

> ⚠️ 之前尝试在 `androidMain` 源码里直接 `import ProjectConfig` 会报 "Unresolved reference"，
> 因为 `buildSrc` 的内容只对 Gradle 配置阶段可见，不对应用运行时代码可见。

## 2. 版本号的自动生成（versionCode）

`versionCode` 不是手填的，而是由 git commit 数量决定：

`app/android/build.gradle.kts`
```kotlin
defaultConfig {
    versionCode = getGitVersionCode()        // = git rev-list --count HEAD
    versionName = ProjectConfig.VERSION_NAME
}
```

`getGitVersionCode()` 在该文件顶部定义（执行 `git rev-list --count HEAD`）。
因此每新增一个 commit，versionCode 自动 +1，无需手动维护。

## 3. 版本名的三处流向

```
ProjectConfig.VERSION_NAME ("1.3.1")
   │
   ├─ defaultConfig.versionName
   │         → 安装包的 versionName（系统「应用信息」里看到的版本）
   │
   ├─ buildConfigField("String", "APP_VERSION_NAME", "...")
   │         → 注入到 BuildConfig.APP_VERSION_NAME（应用内代码读取）
   │
   └─ base.archivesName = APP_NAME + "-v" + VERSION_NAME + "(" + getGitVersionCode() + ")"
             → 决定生成的 APK 文件名
               例：vivo OTA Tracker-v1.3.1(6)-debug.apk
```

**结论**：改 `ProjectConfig.VERSION_NAME` 一处，会同时影响安装包版本名、APK 文件名、
以及应用内显示的版本（见下一节），无需改多处。

## 4. 在应用内显示版本号

因为业务代码不能直接引用 `ProjectConfig`，采用「构建脚本注入 BuildConfig」的方式：

`app/android/build.gradle.kts`
```kotlin
android {
    buildFeatures { buildConfig = true }   // AGP 8+ 默认关闭，需手动开启
    defaultConfig {
        buildConfigField("String", "APP_VERSION_NAME", "\"${ProjectConfig.VERSION_NAME}\"")
    }
}
```

`AndroidAppContext.kt` 提供运行时读取（优先 PackageManager，回退 BuildConfig）：
```kotlin
val versionName: String   // 例："1.3.1"
val versionCode: Long     // 例：6
```

关于页（`vivo/VivoApp.kt` 的 AboutDialog）显示：
```kotlin
val appVersion = "v" + AndroidAppContext.versionName
Text(text = "$appVersion (${AndroidAppContext.versionCode})")  // 显示：v1.3.1 (6)
```

升级版本后 AboutDialog 会自动同步，不再需要像之前那样硬编码 `"v1.3.0"`。

## 5. 版本命名约定

- 遵循语义化版本 `x.y.z`（如 `1.3.1`）。
- 仅修改 `ProjectConfig.VERSION_NAME` 字符串即可，其余自动同步。
- versionCode 不要手动改，交给 git commit 数。
