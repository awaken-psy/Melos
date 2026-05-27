# Melos

针对微信小程序体育锻炼检测系统的 Android LSPosed 反制模块。

## 背景

同济大学课程攻防对抗项目——对方开发微信小程序体育锻炼检测系统（采集 GPS、海拔、轨迹、步频、WiFi、基站等），本组开发反制 app，通过 LSPosed 在 Android Framework 层注入自洽的虚拟传感器数据，绕过多维度检测。

## 技术方案

- **设备**：Pixel 4 XL (Android 13), Magisk 30.7 + Vector(LSPosed) v2.0 + Shamiko v1.2.5
- **原理**：Hook LocationManager / SensorManager / WifiManager / TelephonyManager，注入合成数据
- **跨进程配置**：UI 写入 `/data/local/tmp/melos_config.json`，WeChat 进程中的 hook 读取

## 检测维度与对抗策略

| 检测维度 | 数据来源 | 对抗方式 | 状态 |
|---------|---------|---------|------|
| GPS 经纬度 | LocationManager | 真实采集轨迹 + Catmull-Rom spline 生成 | ✅ |
| GPS 泄漏防护 | Location getter (6 个) | IdentityHashMap 缓存替换真实坐标 | ✅ |
| 海拔高度 | GPS / 气压计 | 真实海拔 profile + 微波动 | ✅ |
| 步频/步数 | 加速度计/步进检测器 | 速度-步频耦合 + hash 白噪声 | ✅ |
| 陀螺仪 | SensorManager | 航向变化率同步 + 步频摆动 | ✅ |
| 磁力计 | SensorManager | 方位角驱动地磁场 + hash 噪声 | ✅ |
| 融合定位 | GMS FusedLocationProvider | getLocations/getLastLocation spoof，缓存一致 | ✅ |
| WiFi AP | WifiManager | KNN 空间查询真实指纹 + RSSI 噪声 | ✅ |
| 基站信息 | TelephonyManager | Unsafe 构建 CellInfo + 真实指纹数据 | ✅ |
| 环境检测 | 文件/包名/Build/反射 | Root/Xposed/SELinux 全维度隐藏 (Layer 1+2+) | ✅ |
| 传感器时间戳 | SensorEvent.timestamp | elapsedRealtimeNanos 单调时钟，GPS/传感器同步 | ✅ |
| 多线程定位 | 多 listener 注册 | 去重 + 单共享 Melos-LocThread | ✅ |
| Location 元数据 | hasBearing/hasSpeed | 确保 hasBearing/hasSpeed 返回 true | ✅ |

## 实测验证

2026-05-28 在 Pixel 4 XL 上通过微信小程序运动追踪实测，各维度 hook 状态：

| 维度 | 拦截路径 | 实测结果 |
|------|---------|---------|
| GPS 位置 | LocationManager (7 种重载) | ✅ 坐标持续更新，速度 3.0-3.5 m/s |
| WiFi | WifiManager.getScanResults | ✅ 返回 7-9 个伪造 AP |
| 基站 | TelephonyManager.getAllCellInfo | ✅ 返回 2 个伪造基站 |
| 加速度计 | SensorManager.registerListener (Handler 重载) | ✅ 步态波形正常注入 |
| 陀螺仪 | SensorManager.registerListener (Handler 重载) | ✅ bearingRate 同步注入 |
| 磁力计 | 微信 native 层直接读取 | ⚠️ Java hook 不可达，地图朝向锥不受控 |
| 步数 | wx.getWeRunData (每日汇总) | ⚠️ 非实时，不影响运动追踪 |

## App 界面 (Material Design 3)

统一 3-tab 界面（底部导航 + MaterialToolbar 标题栏）：

### Tab 1 — 模拟控制
- 场地选择：嘉定大操场 / 同济四平操场（AutoCompleteTextView + ExposedDropdownMenu）
- 配速滑块：3:00 ~ 9:00 min/km（Material Slider）
- 圈数滑块：1 ~ 10 圈
- 预览轨迹按钮（Leaflet 地图 + 高德瓦片）
- 开始/停止按钮（确认对话框，写入配置文件，hook 实时读取）

### Tab 2 — 采集器
- 实时显示 GPS 坐标/精度/WiFi AP 数/基站数（卡片布局）
- 点采集 / 连续采集（前台服务，可熄屏后台）
- WiFi + 基站数据异步刷新（Snackbar 提示）
- 采集列表：查看地图 / 删除 / 导出 JSON

### Tab 3 — 日志
- 异常日志（E/W 级别，默认展开）
- 全部日志（默认折叠，可点击展开）
- 红色 FAB 清除日志

## 轨迹引擎

### 数据流

```
实地采集 (collector) → GPS 清洗 → 多圈中位数平均 → 外推 4m 补偿
    → TrackProfile (Catmull-Rom spline) → TrajectoryGenerator → Hook 注入
```

### 关键模块

| 模块 | 职责 |
|------|------|
| `RealTrackLoader` | 从指纹 JSON 加载轨迹：accuracy 过滤 → spike 去除 → 速度过滤 → 多趟清洗 → 平滑 → 圈检测 → 中位数平均 |
| `GeoUtils` | 球面地理计算：haversine、bearing、destination、offsetMeters、catmullRom、wgs84ToGcj02 |
| `TrackProfile` | 闭合跑道模型，Catmull-Rom spline 插值，切线 bearing |
| `TrajectoryGenerator` | 动态轨迹：真实 speed/altitude profile 插值，加速限制，热身二次曲线，GPS 精度随机游走 |
| `FingerprintDatabase` | WiFi/基站指纹 KNN 空间查询，RSSI 噪声 |

### 传感器注入架构

- 独立 50Hz 高频注入循环（非 GPS 同步），各传感器按自身采样率分频
- 加速度计：三段式波形（蹬地/腾空/落地）+ hash 白噪声
- 气压计：海拔换算 + sin 低频漂移 + hash 噪声
- 磁力计：方位角驱动 + per-axis hash 噪声
- 陀螺仪：角速度同步 + 摆动频率
- SensorEvent 通过 `Unsafe.allocateInstance` 创建，兼容所有 ROM 和进程
- `registerListener` 全部 4 个重载已 hook（含 Handler 参数版本）
- GPS 冷启动模拟：前 20s 精度渐降（5x → 1x）、卫星数从 3 渐增到 9+

### 反检测体系

| 层级 | 检测向量 | 对抗方式 |
|------|---------|---------|
| Layer 1 | 文件系统 su/magisk/busybox | File.exists() 返回 false |
| Layer 1 | root 命令执行 | Runtime.exec / ProcessBuilder 拦截 |
| Layer 1 | Magisk/Xposed 包名 | PackageManager 抛 NameNotFoundException |
| Layer 1 | Build.TAGS test-keys | 替换为 release-keys |
| Layer 1 | Class.forName 探测 Xposed 类 | 抛 ClassNotFoundException |
| Layer 2 | 堆栈跟踪 Xposed 帧 | Throwable/Thread.getStackTrace 过滤 |
| Layer 2 | 系统属性 (ro.debuggable 等) | SystemProperties.get 伪造 |
| Layer 2 | ADB/开发者选项 | Settings.Secure/Global 归零 |
| Layer 2 | ProcessBuilder 命令注入 | 与 Runtime.exec 同策略 |
| Layer 2+ | SELinux/verified-boot 文件 | FileInputStream 拦截 + canRead 返回 false |
| Layer 2+ | getenforce/sestatus 命令 | 命令级拦截 |

## 代码结构

```
app/src/main/kotlin/com/melos/
├── MelosHookEntry.kt              # LSPosed 主入口，全量 Hook 注册
├── MelosConfig.kt                 # 跨进程配置（JSON 文件读写 + 日志）
├── TrajectoryMapActivity.kt       # 轨迹地图预览（Leaflet + 高德瓦片）
├── ui/
│   ├── MainActivity.kt            # 3-tab 主界面 (MD3)
│   ├── SimulateFragment.kt        # 模拟控制（场地/配速/圈数/启停）
│   ├── CollectorFragment.kt       # GPS+WiFi+基站采集
│   └── LogFragment.kt             # 异常日志 + 全部日志
├── collector/
│   ├── CollectorData.kt           # 采集数据类（GPS/WiFi/基站）
│   ├── RecordingService.kt        # 前台录制服务
│   └── MapActivity.kt             # 采集地图预览
├── trajectory/
│   ├── LatLng.kt / TrajectoryPoint.kt
│   ├── GeoUtils.kt                # 球面地理 + Catmull-Rom + GCJ-02
│   ├── TrackProfile.kt            # 闭合跑道 spline 插值
│   ├── TrajectoryGenerator.kt     # 动态轨迹生成
│   └── RealTrackLoader.kt         # 真实轨迹加载与清洗
├── sensor/
│   ├── SensorSimulator.kt         # 多传感器数据生成
│   └── SensorHookManager.kt       # 50Hz 独立注入循环 (4 重载 hook)
├── fingerprint/
│   ├── FingerprintDatabase.kt     # WiFi/基站指纹 KNN 查询
│   ├── WifiCellHookManager.kt     # WifiManager/TelephonyManager Hook
│   └── FingerprintData.kt         # 指纹数据类
└── hide/
    └── AntiDetection.kt           # Root/Xposed/SELinux 全维度隐藏
```

## 单元测试

187 个测试全部通过：

| 测试类 | 数量 | 覆盖范围 |
|--------|------|----------|
| `GeoUtilsTest` | 38 | haversine、bearing、destination、offset、lerp、catmullRom、GCJ-02 |
| `TrackProfileTest` | 22 | 构造、闭合、pointAtDistance、越界包裹、bearing |
| `TrajectoryGeneratorTest` | 25 | 轨迹生成、速度/warmup、海拔、精度、时间倒退、真实 profile 插值 |
| `RealTrackLoaderTest` | 20 | 空数据、精度过滤、spike 清除、圈检测、多圈中位数、profile |
| `SensorSimulatorTest` | 23 | 步数、速度-步频耦合、步进检测器、事件生成 |
| `AntiDetectionLogicTest` | 45 | isSuspiciousPath / isRootCommand 全覆盖 |
| `FingerprintDatabaseTest` | 14 | KNN 查询、RSSI 噪声、边界 |
| `DataSerializationTest` | 12 | JSON 序列化 |

## 开发环境

```bash
# 构建 (需要 JDK 17)
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:assembleDebug

# 安装
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 运行测试
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest

# 查看日志
adb logcat | grep Melos
```

## 已知残留风险

- 🟡 **服务端轨迹统计分析**：轨迹来自同一场地采集数据，防守方收集足够样本后可能通过统计检验筛出异常
- 🟡 **磁力计朝向**：微信地图通过 native 层读取磁力计，Java hook 不可达，地图方向锥不受控（不影响轨迹追踪功能）
- 🟢 **服务端合理性校验**（运动时段、距离/时长比）——当前默认 9km/h 基本合理

## License

MIT
