# 开发环境

## 环境要求

| 工具 | 版本要求 |
|------|---------|
| JDK | **17**（更高版本可能导致 Kotlin 编译器崩溃） |
| Android SDK | API 33 (Android 13) 或更高 |
| Gradle | 项目自带 wrapper，无需单独安装 |
| ADB | Android SDK Platform Tools |

## 构建命令

```bash
# 编译 Debug APK
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:assembleDebug

# 编译 Release APK（需要签名配置）
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:assembleRelease

# 安装到设备
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 运行单元测试
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest

# 清理构建产物
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew clean
```

## 开发调试

### 查看日志

```bash
# Melos 主日志
adb logcat | grep Melos

# LSPosed bridge 日志（包含 hook 调用栈）
adb logcat | grep LSPosed-Bridge

# 配置文件读写日志
adb logcat | grep MelosConfig
```

### 重启微信加载新模块

修改代码后需要重新安装 APK 并重启微信来加载新的 hook：

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.tencent.mm
```

如果是首次在 LSPosed 中勾选模块，需要完全重启手机：
```bash
adb reboot
```

### 配置文件调试

配置文件位于 `/data/local/tmp/melos_config.json`，可以直接查看和修改：

```bash
# 查看当前配置
adb shell su -c "cat /data/local/tmp/melos_config.json"

# 查看指纹数据
adb shell su -c "cat /data/local/tmp/melos_fingerprint.json"

# 删除配置文件（重置）
adb shell su -c "rm /data/local/tmp/melos_config.json"
```

### 设备上的关键路径

| 路径 | 用途 |
|------|------|
| `/data/local/tmp/melos_config.json` | 跨进程配置文件 |
| `/data/local/tmp/melos_fingerprint.json` | WiFi/基站指纹数据 |
| `/data/adb/modules/zygisk_vector/` | LSPosed (Vector) 模块目录 |
| `/data/adb/shamiko/whitelist` | Shamiko 白名单模式标记文件 |

## 项目依赖

- **Xposed API**：`compileOnly(files("libs/xposed-api-82.jar"))` — 编译时依赖，运行时由 LSPosed 提供
- **Material Design 3**：AndroidX Material3 组件库
- **Leaflet**：WebView 内嵌的地图库，用于轨迹预览
- **org.json:json:20231013**：单元测试中使用的 JSON 库（Android SDK 内置版本在单元测试中不可用）

## 注意事项

- **JDK 版本**：必须使用 JDK 17。JDK 21/25 EA 会导致 Kotlin 编译器崩溃
- **Xposed API 版本**：使用 API 82，对应 LSPosed/Vector 的接口版本
- **ProGuard**：Debug 版本不混淆，Release 版本需要配置 keep 规则以保留 hook 入口类
- **签名**：Debug 使用默认 debug keystore，Release 需要配置自己的签名密钥
