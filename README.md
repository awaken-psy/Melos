# Melos

一个针对微信小程序体育锻炼检测系统反制的 Android LSPosed 模块。

## 背景

本项目是同济大学课程攻防对抗项目——一组开发了微信小程序体育锻炼检测系统（采集 GPS 定位、海拔、运动轨迹、步频等数据），本组负责开发反制 app，通过 LSPosed 框架在 Android 系统层注入模拟传感器数据，以绕过其多维度检测机制。

## 技术方案

- **平台**：Android（Google Pixel 4 XL 等可 Root 设备）
- **Root**：Magisk
- **Hook 框架**：LSPosed
- **核心原理**：在 Android Framework 层拦截 LocationManager 和 SensorManager，注入自洽的虚拟传感器数据

## 检测维度与对抗策略

| 检测维度 | 数据来源 | 对抗方式 |
|---------|---------|---------|
| GPS 经纬度 | LocationManager | 生成符合围栏约束的轨迹坐标 |
| 海拔高度 | GPS 高程 / 气压计 | 使海拔变化与场地地形一致 |
| 步频 | 加速度计 | 模拟加速度波形，匹配目标步频 |
| 运动轨迹 | GPS 点序列 | 生成自然的人跑轨迹（速度、方向合理） |

## 开发环境

```bash
sudo apt install adb fastboot -y
# 详细环境配置见文档
```

## License

MIT
