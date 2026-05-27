# 检测维度与对抗策略

微信小程序体育锻炼检测系统会从多个维度采集数据来判断运动真实性。Melos 对每个维度都有对应的对抗策略。

## 检测维度总览

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

## 各维度详解

### GPS 经纬度

检测方通过 `LocationManager` 的 7 种方法重载获取 GPS 坐标，包括 `requestLocationUpdates`、`getLastKnownLocation` 等。Melos hook 了全部 7 种重载，返回由真实采集轨迹经 Catmull-Rom spline 插值生成的坐标。同时模拟 GPS 冷启动：前 20 秒精度渐降（5x → 1x），卫星数从 3 渐增到 9+，还原真实 GPS 初始行为。

### GPS 泄漏防护

Android 系统有 6 个不同的 Location getter 方法（`getLastKnownLocation`、`getLocation` 等），如果不全部 hook，某些 getter 会泄漏真实坐标。Melos 使用 `IdentityHashMap` 缓存，确保所有 getter 返回一致的伪造坐标。

### 海拔高度

海拔数据来源于 GPS 和气压计。Melos 使用真实采集的海拔 profile 加上微小波动，确保海拔变化合理。气压计数据通过海拔换算 + sin 低频漂移 + hash 噪声生成。

### 步频/步数

通过加速度计和步进检测器传感器获取。Melos 实现了速度-步频耦合模型——根据当前运动速度动态计算合理步频（约 160-190 步/分），并叠加 hash 白噪声使数据自然。

### WiFi AP

小程序可能通过 `WifiManager.getScanResults` 获取周围 WiFi AP 列表用于位置验证。Melos 预先采集了场地的真实 WiFi 指纹数据库，运行时通过 KNN 空间查询找到当前位置附近的真实 AP，并在 RSSI 上添加噪声。这样返回的 WiFi 列表与实际场地一致。

### 基站信息

类似 WiFi，通过 `TelephonyManager.getAllCellInfo` 获取基站数据。Melos 使用 `Unsafe.allocateInstance` 构建 `CellInfo` 对象（因为这些类没有公开构造器），填入从真实指纹数据中查询到的基站信息。

### 环境检测

检测方可能通过多种手段检测 root/Xposed 环境：
- 检查文件系统中是否存在 `su`、`magisk`、`busybox` 等文件
- 尝试执行 root 命令
- 检查 Magisk/Xposed 包名是否安装
- 检查 `Build.TAGS` 是否为 `test-keys`
- 通过 `Class.forName` 探测 Xposed 相关类
- 分析堆栈跟踪中的 Xposed 帧
- 读取系统属性 `ro.debuggable` 等
- 检查 SELinux 状态

Melos 的 `AntiDetection` 模块在 Layer 1+2+ 层面对这些检测进行拦截和伪造。

### 传感器时间戳

传感器事件的时间戳必须使用单调时钟（`elapsedRealtimeNanos`），且 GPS 和各传感器的时间戳必须同步。Melos 确保所有注入的传感器事件使用一致的单调时钟。

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

### 实测说明

- **GPS**：实测坐标持续更新，速度稳定在 3.0-3.5 m/s，加速度曲线自然平滑，冷启动精度渐降模拟正常
- **WiFi/基站**：返回数量和信号强度均与场地实地采集数据一致
- **加速度计/陀螺仪**：传感器监听器成功注册，步态波形和角速度数据持续注入
- **磁力计**（⚠️）：微信地图通过 native 层（NDK）直接读取磁力计硬件，绕过了 Java SensorManager，因此 Java 层 hook 无法拦截。地图上的方向指示锥不受控，但不影响轨迹追踪功能
- **步数**（⚠️）：`wx.getWeRunData` 是微信提供的每日步数汇总 API，不是实时的，对运动追踪过程无影响

## 反检测体系

### Layer 1 — 基础环境隐藏

| 检测向量 | 对抗方式 |
|---------|---------|
| 文件系统 su/magisk/busybox | `File.exists()` 返回 false |
| root 命令执行 | `Runtime.exec` / `ProcessBuilder` 拦截 |
| Magisk/Xposed 包名 | `PackageManager` 抛 `NameNotFoundException` |
| Build.TAGS test-keys | 替换为 `release-keys` |
| Class.forName 探测 Xposed 类 | 抛 `ClassNotFoundException` |

### Layer 2 — 深度痕迹隐藏

| 检测向量 | 对抗方式 |
|---------|---------|
| 堆栈跟踪 Xposed 帧 | `Throwable`/`Thread.getStackTrace` 过滤 |
| 系统属性 (ro.debuggable 等) | `SystemProperties.get` 伪造 |
| ADB/开发者选项 | `Settings.Secure`/`Global` 归零 |
| ProcessBuilder 命令注入 | 与 `Runtime.exec` 同策略 |

### Layer 2+ — Native 层隐藏

| 检测向量 | 对抗方式 |
|---------|---------|
| SELinux/verified-boot 文件 | `FileInputStream` 拦截 + `canRead` 返回 false |
| getenforce/sestatus 命令 | 命令级拦截 |

Layer 2+ 的检测需要 Shamiko 配合，在 `/proc/self/maps` 中过滤注入痕迹。
