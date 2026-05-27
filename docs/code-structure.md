# 代码结构

## 目录概览

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
│   ├── LatLng.kt                  # 坐标数据类
│   ├── TrajectoryPoint.kt         # 轨迹点数据类
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

## 核心入口

### MelosHookEntry.kt

LSPosed 模块主入口，实现了 `IXposedHookLoadPackage` 接口。当目标应用（微信）进程启动时被调用，负责：

- 判断当前进程是否为目标应用（微信）
- 初始化配置读取器（`MelosConfig`）
- 注册全部 Hook：
  - `LocationManager`（GPS 位置，7 种重载）
  - `SensorManager`（传感器，4 种重载）
  - `WifiManager`（WiFi 扫描结果）
  - `TelephonyManager`（基站信息）
  - `AntiDetection`（反检测拦截）
- 启动传感器注入循环
- 启动位置注入线程（`Melos-LocThread`）

### MelosConfig.kt

跨进程配置管理。Melos 的 UI 进程和微信进程是独立的，通过共享 JSON 文件通信：

- **配置文件路径**：`/data/local/tmp/melos_config.json`
- **写入**：UI 进程在用户点击"开始"/"停止"时写入
- **读取**：Hook 进程在每次注入前读取最新配置
- **日志**：`log()` 方法同时写入 logcat 和内存日志列表
- **Root fallback**：Android 13 上普通应用无权写 `/data/local/tmp/`，通过 root shell 写入

## 模块详解

### trajectory/ — 轨迹生成

负责从原始采集数据到可注入轨迹的完整流水线。

- **GeoUtils.kt**：纯数学工具，haversine 距离、方位角、Catmull-Rom 插值、GCJ-02 偏移
- **TrackProfile.kt**：将一组 GPS 点构建为闭合跑道，支持按距离插值坐标和方向
- **TrajectoryGenerator.kt**：时间驱动的轨迹生成器，支持速度变化、热身、加速限制
- **RealTrackLoader.kt**：数据清洗管道，从原始采集 JSON 到可用的 TrackProfile

### sensor/ — 传感器注入

- **SensorSimulator.kt**：根据当前运动状态（速度、方向、时间）生成各传感器的模拟数据
- **SensorHookManager.kt**：管理传感器 hook 的安装和 50Hz 注入循环

传感器数据生成的关键：各传感器不是独立随机的，而是基于同一个物理状态（速度、方向、步态阶段）生成，确保数据之间的自洽性。

### fingerprint/ — 指纹查询

- **FingerprintDatabase.kt**：加载场地指纹 JSON，KNN 空间查询，RSSI 噪声生成
- **WifiCellHookManager.kt**：Hook WifiManager 和 TelephonyManager，返回伪造的 WiFi/基站数据
- **FingerprintData.kt**：数据类定义（WifiAp、CellTower、FingerprintSample 等）

### hide/ — 反检测

- **AntiDetection.kt**：多层反检测拦截，覆盖文件系统、命令执行、包名查询、系统属性、堆栈跟踪等检测向量

### ui/ — 界面

Material Design 3 界面，使用 Fragment + BottomNavigationView 架构。详见 [App 界面文档](app-ui.md)。

### collector/ — 采集器

实地数据采集功能，包含数据类、前台录制服务、地图预览 Activity。
