# 单元测试

Melos 共有 **187 个单元测试**，全部通过，覆盖核心算法和数据处理的各个关键路径。

## 测试概览

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

## 各测试类详解

### GeoUtilsTest (38 tests)

测试所有球面地理计算函数的正确性和边界情况：

- **haversine**：已知距离验证、零距离、对跖点、大圆距离精度
- **bearing**：正北方向、正东方向、对角方向、跨越赤道/本初子午线
- **destination**：从已知点出发，验证到达点坐标精度
- **offsetMeters**：小距离偏移的线性近似精度
- **lerp**：线性插值边界值（0、1、超出范围）
- **catmullRom**：4 控制点样条插值端点匹配、中间值单调性
- **GCJ-02**：坐标偏移方向和量级正确（约 500m 偏移）

### TrackProfileTest (22 tests)

测试闭合跑道几何模型：

- **构造**：从 GPS 点列表构建 profile
- **闭合性**：起点和终点坐标一致
- **pointAtDistance**：任意距离处的插值坐标正确
- **越界包裹**：超出总距离时自动从起点重新开始
- **bearing**：插值点方向角计算正确，弯道处方向变化平滑

### TrajectoryGeneratorTest (25 tests)

测试动态轨迹生成的各种场景：

- **基本生成**：按时间推进生成连续轨迹点
- **速度变化**：速度 profile 插值正确
- **热身曲线**：起步阶段加速度合理（二次曲线渐增）
- **海拔**：海拔数据随轨迹变化
- **精度**：GPS 精度随机游走在合理范围
- **时间倒退**：系统时间跳跃等异常情况的处理
- **真实 profile 插值**：从真实 speed/altitude profile 插值

### RealTrackLoaderTest (20 tests)

测试数据清洗管道的各环节：

- **空数据**：空输入、单点输入的处理
- **精度过滤**：GPS 精度差于阈值的点被丢弃
- **Spike 清除**：相邻点突变超过阈值的被平滑
- **速度过滤**：不合理的高速点被移除
- **圈检测**：自动检测跑道闭合点
- **多圈中位数**：多圈数据取中位数后精度提升
- **Profile 输出**：最终输出的 TrackProfile 质量合格

### SensorSimulatorTest (23 tests)

测试传感器数据生成逻辑：

- **步数计算**：根据时间和步频计算累计步数
- **速度-步频耦合**：不同速度下步频值合理（走路 ~120、慢跑 ~160、快跑 ~180 步/分）
- **步进检测器**：基于步态周期正确触发步进事件
- **事件生成**：生成的 SensorEvent 包含正确的传感器类型、值数组、时间戳

### AntiDetectionLogicTest (45 tests)

测试反检测路径匹配和命令识别逻辑：

- **isSuspiciousPath**：`/system/bin/su`、`/system/xbin/su`、`/data/adb/magisk`、`/system/app/Superuser.apk` 等路径正确识别
- **isRootCommand**：`su`、`sudo`、`magisk`、`supersu`、`busybox` 等命令正确识别
- **误报测试**：`/system/bin/surfaceflinger`、`sudoers` 文件路径等不应被标记
- **大小写混合**：`Su`、`SUDO` 等变体识别
- **命令参数**：带参数的命令（如 `su -c "..."`）正确识别

### FingerprintDatabaseTest (14 tests)

测试指纹数据库查询：

- **KNN 查询**：给定点坐标，返回最近的 K 个指纹点
- **距离排序**：返回结果按距离升序排列
- **RSSI 噪声**：添加噪声后 RSSI 在合理波动范围内
- **边界情况**：查询点远离所有指纹数据时的处理
- **空数据库**：无数据时的优雅降级

### DataSerializationTest (12 tests)

测试数据类的 JSON 序列化/反序列化：

- **往返一致性**：序列化后反序列化，数据不变
- **采集数据**：GPS 点、WiFi AP、基站数据的完整序列化
- **指纹数据**：FingerprintSample、FingerprintResult 的序列化
- **配置数据**：MelosConfig 的序列化格式正确

## 运行测试

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest
```

测试位于 `app/src/test/kotlin/com/melos/` 目录，与源码包结构对应。
