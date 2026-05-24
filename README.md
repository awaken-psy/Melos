# Melos

一个针对微信小程序体育锻炼检测系统反制的 Android LSPosed 模块。

## 背景

本项目是同济大学课程攻防对抗项目——一组开发了微信小程序体育锻炼检测系统（采集 GPS 定位、海拔、运动轨迹、步频等数据），本组负责开发反制 app，通过 LSPosed 框架在 Android 系统层注入模拟传感器数据，以绕过其多维度检测机制。

## 技术方案

- **平台**：Android（Google Pixel 4 XL 等可 Root 设备）
- **Root**：Magisk 30.7
- **Hook 框架**：LSPosed (Vector v2.0)
- **核心原理**：在 Android Framework 层拦截 LocationManager 和 SensorManager，注入自洽的虚拟传感器数据

## 检测维度与对抗策略

| 检测维度 | 数据来源 | 对抗方式 | 状态 |
|---------|---------|---------|------|
| GPS 经纬度 | LocationManager | 生成符合围栏约束的轨迹坐标 | ✅ 已实现 |
| 海拔高度 | GPS 高程 / 气压计 | 使海拔变化与场地地形一致 | ✅ 已实现 |
| 步频 | 加速度计 | 模拟加速度波形，匹配目标步频 | ✅ 已实现 |
| 运动轨迹 | GPS 点序列 | 标准 400m 椭圆跑道，弯道密集采样，自然速度/方向 | ✅ 已实现 |
| 磁力计/陀螺仪 | SensorManager | 生成自洽的运动传感器数据 | ✅ 已实现 |
| 融合定位 | GMS FusedLocationProvider | spoof getLocations/getLastLocation（结果互相一致） | ✅ 已实现 |
| 环境检测 | 文件/包名/Build/反射 | 隐藏 Root 与 LSPosed/Xposed 痕迹 | ✅ 已实现 |

## 项目进度

### ✅ 已完成

- **开发环境搭建**
  - Gradle 8.7.0 + AGP 8.5.2 + Kotlin 1.9.24
  - Xposed API 82 集成
  - Android 项目脚手架完整配置

- **设备环境**
  - Pixel 4 XL (coral) 已 root
  - Magisk 30.7 + Vector (LSPosed) v2.0 已安装
  - Zygisk 注入已验证工作正常

- **轨迹引擎核心** (`com.melos.trajectory`)
  - `LatLng` - 经纬度坐标数据类
  - `TrajectoryPoint` - 轨迹点（含时间戳、速度、海拔）
  - `GeoUtils` - 球面地理计算工具
    - haversineMeters() - 计算两点间球面距离
    - lerp() - 经纬度线性插值
    - bearingDeg() - 计算方位角
    - destination() - 沿方位角计算目标点
    - offsetMeters() - 米级偏移
  - `TrackProfile` - 跑道循环模型
    - 支持多航点定义的闭合跑道
    - 基于沿轨距离的精确定位
  - `TrajectoryGenerator` - 动态轨迹生成器
    - 自然的速度变化（±15%）
    - 弯道减速模型
    - Perlin 噪声路径游走（±2m）
    - 海拔变化模拟

- **传感器模拟系统** (`com.melos.sensor`)
  - `SensorSimulator` - 传感器模拟协调器
    - 多传感器时间同步
    - GPS-传感器数据一致性维护
    - 步数统计
  - `SensorHookManager` - SensorManager Hook
    - 拦截加速度计、气压计、磁力计、陀螺仪
    - 跟踪活跃的 sensor listener
    - 定时注入合成传感器事件

- **GPS Hook 入口** (`com.melos.MelosHookEntry`)
  - 实现 `IXposedHookLoadPackage`
  - Hook `LocationManager.getLastKnownLocation()`
  - Hook `requestLocationUpdates()` 全部重载（经典 String+long+float、API 31+ LocationRequest、
    无 Looper / 有 Looper / Executor），一律 `beforeHookedMethod` + `param.result = null`
    拦截真实注册，改向监听器推送合成轨迹
  - Hook `getCurrentLocation()`（API 30+ provider 版 / API 31+ LocationRequest 版），
    立即通过原始 Executor 向 Consumer 交付伪造位置
  - Hook `flushLocations()` → 阻断，防止泄漏真实静止 fix
  - PendingIntent 变体 → 阻断（listener 路径已覆盖正常用法）
  - Hook `FusedLocationProvider` (GMS) 的 `getLocations()`/`getLastLocation()`，
    共享 `recentLocations` 缓存保证二者一致
  - 内置标准 400m 椭圆跑道（`buildTongjiTrack()`：84.39m 直道 + 36.5m 半径弯道）
  - 动态轨迹生成集成
  - 测试位置：同济大学四平校区 (31.2506, 121.5045)

- **反检测 / 环境隐藏** (`com.melos.hide.AntiDetection`)
  - **Layer 1**（基础）:
    - `File.exists()`：su / Magisk / Xposed 等路径返回 false
    - `Runtime.exec()` / `ProcessBuilder.start()`：`su` 类命令抛 `IOException`
    - `PackageManager`：隐藏 Magisk/LSPosed 管理器包并过滤已安装列表
    - `Build.TAGS` / `FINGERPRINT`：`test-keys` → `release-keys`
    - `Class.forName()`：Xposed 框架类抛 `ClassNotFoundException`
  - **Layer 2**（深层）:
    - `Throwable.getStackTrace()` / `Thread.getStackTrace()`：过滤 Xposed/LSPosed 帧
    - `SystemProperties.get()`：`ro.debuggable` → `0`、`ro.secure` → `1` 等
    - `Settings.Secure/Global`：`adb_enabled` → `0`、`development_settings_enabled` → `0`

- **模块部署**
  - APK 构建成功 (6MB+)
  - LSPosed 识别并启用模块
  - 作用域配置：系统框架 (system) / WeChat (com.tencent.mm)

### 🔄 代码结构

```
app/src/main/kotlin/com/melos/
├── MelosHookEntry.kt              # 主入口，所有 Hook 的注册点
├── hide/
│   └── AntiDetection.kt           # Root/Xposed 环境隐藏
├── trajectory/
│   ├── LatLng.kt                  # 经纬度数据类
│   ├── GeoUtils.kt                # 地理计算工具
│   ├── TrackProfile.kt            # 跑道几何模型
│   └── TrajectoryGenerator.kt     # 动态轨迹生成器
└── sensor/
    ├── SensorSimulator.kt         # 传感器数据模拟核心
    └── SensorHookManager.kt       # SensorManager Hook 实现
```

### ⏳ 下一步

1. **测试验证**
   - 安装 WeChat 测试实际注入效果
   - 验证 GPS/传感器数据是否正确注入到微信小程序
   - 调整轨迹参数以更符合实际跑步行为

2. **参数调优**
   - 根据实际检测系统的阈值调整速度/步频/精度参数
   - 添加更多预设跑道模板
   - 优化角点检测算法

### ⚠️ 已知残留风险

- 🔴 **WiFi/基站交叉验证**：尚未 Hook `WifiManager`/`TelephonyManager`。防守方
  读取 WiFi BSSID 或基站 CID/LAC，会发现"GPS 在移动但接入点/基站恒定不变"的矛盾——
  当前最大缺口。
- 🟡 **Native 层 maps 扫描**：通过 JNI 直接 `openat("/proc/self/maps")` 检查注入的
  `.so` 文件属于 native 层检测，Java Xposed 无法拦截，需 Shamiko/Zygisk 白名单等
  方案辅助。
- 🟢 **服务端合理性校验**（运动时段、距离/时长比、轨迹去相关）—— 当前 9km/h 基本
  合理，需实测后按阈值调参。

## 开发环境

```bash
# Android SDK
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/tools

# 构建
JAVA_HOME=/usr/lib/jvm/java-1.17.0-openjdk-amd64 ./gradlew assembleDebug

# 安装到设备
adb install app/build/outputs/apk/debug/app-debug.apk

# 查看 LSPosed 日志
adb shell su -c "cat /data/adb/lspd/log/modules_*.log"

# 查看模块日志
adb logcat | grep Melos
```

## License

MIT
