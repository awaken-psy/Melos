# Melos

针对微信小程序体育锻炼检测系统的 Android LSPosed 反制模块。

## 背景

同济大学课程攻防对抗项目——对方开发微信小程序体育锻炼检测系统（采集 GPS、海拔、轨迹、步频、WiFi、基站等），本组开发反制 app，通过 LSPosed 在 Android Framework 层注入自洽的虚拟传感器数据，绕过多维度检测。

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

- **设备**：Pixel 4 XL (Android 13), Magisk 30.7 + Vector(LSPosed) v2.0 + Shamiko v1.2.5
- **原理**：Hook LocationManager / SensorManager / WifiManager / TelephonyManager，注入合成数据
- **跨进程配置**：UI 写入 `/data/local/tmp/melos_config.json`，WeChat 进程中的 hook 读取

## License

MIT
