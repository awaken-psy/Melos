# Melos

针对微信小程序体育锻炼检测系统的 Android LSPosed 反制模块。

## 背景

xx大学微信小程序体育锻炼检测系统反制 app（采集 GPS、海拔、轨迹、步频、WiFi、基站等），通过 LSPosed 在 Android Framework 层注入自洽的虚拟传感器数据，绕过多维度检测。

## 文档导航

| 文档 | 说明 |
|------|------|
| [快速开始](docs/getting-started.md) | 从零开始的安装使用教程：手机要求、Root、LSPosed、构建部署、使用方法 |
| [检测维度与实测验证](docs/detection-and-verification.md) | 对方检测手段、Melos 对抗策略、Pixel 4 XL 实测结果、反检测体系 |
| [App 界面](docs/app-ui.md) | Material Design 3 三页界面设计：模拟控制、采集器、日志 |
| [轨迹引擎](docs/trajectory-engine.md) | 数据流水线、Catmull-Rom 轨迹生成、传感器注入架构、GPS 冷启动模拟 |
| [代码结构](docs/code-structure.md) | 源码目录、各模块职责、核心入口详解 |
| [单元测试](docs/unit-tests.md) | 187 个测试的覆盖范围和各测试类详解 |
| [开发环境](docs/development.md) | JDK 17 构建、ADB 调试、日志查看、设备路径、项目依赖 |
| [已知残留风险](docs/risks.md) | 服务端统计分析、磁力计朝向、合理性校验等残留风险分析 |

## 技术方案

Melos 是一个 LSPosed (Xposed) 模块，运行在已 root 的 Android 设备上。通过 Zygisk 在目标应用（微信）进程启动时注入，利用 Xposed 的方法 hook 机制拦截系统 API 调用，替换为自洽的虚拟数据。

### 整体架构

- **Melos App（UI 进程）**：Material Design 3 界面，负责场地选择、参数配置、轨迹预览、实地数据采集。配置通过共享 JSON 文件传递给 hook 进程。
- **Hook 模块（微信进程内）**：在微信启动时由 LSPosed 加载，hook 系统 API（LocationManager / SensorManager / WifiManager / TelephonyManager），读取配置后注入虚拟的 GPS、传感器、WiFi、基站数据。

### 数据注入原理

Android 应用的位置和传感器数据都通过系统服务获取（`LocationManager`、`SensorManager` 等）。Melos 在 Framework 层拦截这些 API 调用：

- **GPS**：hook `LocationManager` 的全部 7 种重载，返回由 Catmull-Rom spline 插值生成的轨迹坐标
- **传感器**：独立 50Hz 注入循环，生成加速度计、陀螺仪、气压计、磁力计数据
- **WiFi/基站**：从预先采集的真实指纹数据库中通过 KNN 查询返回一致的伪造数据
- **反检测**：hook 文件系统、命令执行、包名查询等 API，隐藏 root 和 Xposed 环境

### 跨进程通信

Melos App 和 hook 运行在不同进程（后者在微信进程内），通过共享文件 `/data/local/tmp/melos_config.json` 通信。UI 写入配置，hook 读取后实时生效，无需重启微信。

### 测试设备

Pixel 4 XL (Android 13), Magisk 30.7 + Vector(LSPosed) v2.0 + Shamiko v1.2.5

## License

MIT
