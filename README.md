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
| 海拔高度 | GPS 高程 / 气压计 | 使海拔变化与场地地形一致 | ⏳ 待开发 |
| 步频 | 加速度计 | 模拟加速度波形，匹配目标步频 | ⏳ 待开发 |
| 运动轨迹 | GPS 点序列 | 生成自然的人跑轨迹（速度、方向合理） | 🔄 进行中 |

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

- **轨迹引擎核心**
  - `LatLng` - 经纬度坐标数据类
  - `TrajectoryPoint` - 轨迹点（含时间戳）
  - `GeoUtils` - 球面地理计算工具
    - haversineMeters() - 计算两点间球面距离
    - lerp() - 经纬度线性插值
    - bearingDeg() - 计算方位角
  - `TrackProfile` - 跑道循环模型
    - 支持多航点定义的闭合跑道
    - 基于沿轨距离的精确定位

- **GPS Hook 入口**
  - `MelosHookEntry` 实现 `IXposedHookLoadPackage`
  - Hook `LocationManager.getLastKnownLocation()`
  - Hook `LocationListener.onLocationChanged()` 回调
  - 测试位置：同济大学四平校区 (31.2503, 121.5045)

- **模块部署**
  - APK 构建成功 (5.3MB)
  - LSPosed 识别并启用模块
  - 作用域配置：系统框架 (system)
  - LSPosed Bridge 确认加载 `MelosHookEntry` 类

### 🔄 进行中

- 动态轨迹生成算法
- 轨迹与时间戳的自洽性优化

### ⏳ 下一步

1. **测试验证**
   - 安装 WeChat 测试实际注入效果
   - 验证 GPS 数据是否正确注入到微信小程序

2. **传感器扩展**
   - 添加气压计海拔模拟
   - 添加加速度计步频模拟
   - 确保多传感器数据时间同步

3. **轨迹优化**
   - 实现更自然的轨迹生成（速度变化、转弯平滑）
   - 添加多种预设跑道模板

## 开发环境

```bash
# Android SDK
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/tools

# 构建
JAVA_HOME=/usr/lib/jvm/java-1.17.0-openjdk-amd64 ./gradlew assembleDebug

# 安装到设备
adb install app/build/outputs/apk/debug/app-debug.apk
```

## License

MIT
