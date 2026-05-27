# 快速开始

本章节面向零基础用户，从选购手机到完成配置的全流程。

## 1. 手机要求

| 项目 | 要求 |
|------|------|
| 系统 | **Android 10 ~ 13**（14+ Magisk 兼容性不稳定） |
| 品牌 | 推荐 **Google Pixel**（bootloader 解锁简单，系统干净） |
| Root | 必须已 root（Magisk） |
| 存储 | 至少 2GB 剩余空间 |

> 其他品牌（小米、一加等）也可以，但需要自行查找该机型的解锁 bootloader 方法。Samsung 部分机型有 Knox 风险，需谨慎。

为什么需要 root？Melos 是一个 LSPosed 模块，需要在 Android Framework 层注入 hook 来拦截和修改系统 API（GPS、传感器、WiFi 等）。这要求设备已 root 并安装了 LSPosed 框架。

## 2. 解锁 Bootloader

以 Pixel 为例：

```bash
# 1. 手机开启开发者选项：设置 → 关于手机 → 连点"版本号" 7 次
# 2. 进入开发者选项，开启"OEM 解锁"和"USB 调试"
# 3. 连接电脑，确认 adb 可用
adb devices

# 4. 进入 fastboot 模式
adb reboot bootloader

# 5. 解锁 bootloader（会清除所有数据）
fastboot flashing unlock
# 手机上用音量键选确认，按电源键确认
```

解锁后手机会恢复出厂设置，重新设置后再次开启 USB 调试。

> Bootloader 解锁是 root 的前提。解锁后才能刷入修改过的 boot 镜像（Magisk）。注意：解锁会清除所有用户数据，请提前备份。

## 3. 安装 Magisk（获取 Root）

```bash
# 1. 从 https://github.com/topjohnwu/Magisk/releases 下载最新 Magisk APK
# 2. 安装到手机
adb install Magisk.apk

# 3. 提取当前系统 boot.img
#    方法 A：从官方工厂镜像中提取（Pixel）
#      从 https://developers.google.com/android/images 下载对应版本的工厂镜像
#      解压后找到 image-设备代号.zip → 里面的 boot.img
#
#    方法 B：从手机中提取
adb shell su -c "dd if=/dev/block/by-name/boot$(getprop ro.boot.slot_suffix) of=/sdcard/boot.img"
adb pull /sdcard/boot.img .

# 4. 打开手机上的 Magisk app → "安装" → 选择 boot.img → 开始修补
#    修补完成后会在 Download 目录生成 magisk_patched-xxxxx.img

# 5. 拉取修补后的镜像并刷入
adb pull /sdcard/Download/magisk_patched-xxxxx.img .
adb reboot bootloader
fastboot flash boot magisk_patched-xxxxx.img
fastboot reboot

# 6. 开机后打开 Magisk app，显示版本号即 root 成功
```

> **重要**：刷入前请确保 boot.img 与手机当前系统版本完全匹配，否则可能无法开机。如果刷错导致无法开机，可以用原始 boot.img 刷回恢复：`fastboot flash boot boot.img`。

Magisk 是目前最主流的 Android root 方案。它通过修补 boot 镜像中的 ramdisk 来注入 su daemon，同时提供 Zygisk（类似 Xposed 的注入框架）和模块系统。

## 4. 安装 LSPosed 框架

原版 LSPosed 已停更，本项目使用 **Vector**（JingMatrix 维护的 LSPosed 续作）：

```bash
# 1. 从 https://github.com/JingMatrix/LSPosed/releases 下载 Vector zip
#    选择 zygisk 版本（如 Vector-xxx-zygisk-release.zip）

# 2. 推送到手机
adb push Vector-xxx-zygisk-release.zip /sdcard/

# 3. 打开 Magisk app → 模块 → 从本地安装 → 选择该 zip
#    安装完成后重启手机

# 4. 重启后打开 LSPosed 管理器
#    LSPosed 管理器没有桌面图标，通过寄生方式启动：
adb shell am start -c "org.lsposed.manager.LAUNCH_MANAGER" "com.android.shell/.BugreportWarningActivity"
#    或在手机上拨号界面输入 *#*#5776733#*#*（LSPosed）
```

LSPosed（及其继任者 Vector）是 Xposed 框架的现代实现。它通过 Zygisk 在应用进程启动时注入，允许模块 hook 任意 Java 方法的调用和返回值。Melos 正是通过 LSPosed 来 hook 系统 LocationManager、SensorManager 等 API，从而注入虚拟数据。

管理器采用"寄生"设计——没有独立的 app 图标，通过隐藏的 Activity 启动，减少被检测的风险。

## 5. 安装 Shamiko（隐藏 Root 痕迹）

Shamiko 用于对目标应用隐藏 root 和 Xposed 注入痕迹，防止被检测：

```bash
# 1. 从 https://github.com/HuskyDG/sudosite 下载 Shamiko zip（或从 Magisk 模块仓库搜索）
# 2. 同样通过 Magisk app 模块安装，重启

# 3. 启用白名单模式（只对白名单外的应用隐藏）
adb shell su -c "touch /data/adb/shamiko/whitelist"
```

> 白名单模式下，只有加入白名单的应用才能看到 root 痕迹（比如 Magisk 自身需要看到）。Melos 需要访问 root 写入配置文件，但不建议把微信加入白名单。

Shamiko 工作在 Zygisk 层面，会在 `/proc/self/maps` 中过滤掉 Magisk/Xposed 注入的 `.so` 文件痕迹。这是反检测的关键一环——许多检测手段会扫描进程内存映射来发现注入证据。

## 6. 构建并安装 Melos

```bash
# 前提：电脑已安装 JDK 17 和 Android SDK

# 1. 克隆项目
git clone https://github.com/你的用户名/Melos.git
cd Melos

# 2. 编译
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:assembleDebug

# 3. 安装到手机
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> 如果没有 JDK 17 环境，也可以在项目 GitHub Releases 页面直接下载预编译的 APK。

注意事项：
- 必须使用 **JDK 17**，更高版本（如 JDK 21/25）可能导致 Kotlin 编译器崩溃
- 首次编译会下载 Gradle 和依赖，需要网络环境
- 如果遇到 SDK 版本问题，确认 `ANDROID_HOME` 环境变量指向正确的 SDK 路径

## 7. 在 LSPosed 中启用模块

```
1. 打开 LSPosed 管理器（见第 4 步的方法）
2. 进入 "模块" 页面
3. 找到 "Melos"，点击进入
4. 勾选目标应用：
     - ✅ 微信 (com.tencent.mm)
     - ✅ Melos 自身 (com.melos)
5. 保存并强制停止微信（或直接重启手机）
```

> 首次启用后必须**重启微信**（或重启手机），模块才会加载生效。

为什么需要勾选 Melos 自身？Melos 的 UI 进程需要写入配置文件到 `/data/local/tmp/melos_config.json`，在 Android 13 上直接写入会触发 EACCES 错误，Melos 通过 root shell fallback 来写入，但 hook 层面的文件保护逻辑可能影响自身，所以需要在 LSPosed 中也勾选自己。

强制停止微信的方法：`adb shell am force-stop com.tencent.mm`

## 8. 使用 Melos

安装配置完成后，打开 Melos app：

1. **选择场地**：底部 Tab 1 "模拟" → 从下拉菜单选择场地（如"嘉定大操场"）
2. **设置配速**：拖动滑块调整目标配速（3:00 ~ 9:00 min/km）
3. **设置圈数**：拖动滑块选择圈数（1 ~ 10 圈）
4. **预览轨迹**（可选）：点击"预览轨迹"在地图上查看模拟路线
5. **开始模拟**：点击"开始"按钮，确认后 Melos 写入配置，hook 立即生效
6. **打开微信小程序**：切到微信，打开目标体育锻炼小程序，开始运动
7. **停止模拟**：回到 Melos，点击"停止"

工作原理：Melos UI 将配置写入共享配置文件，微信进程中已加载的 hook 实时读取该文件。当你点击"开始"后，hook 立即开始注入虚拟的 GPS、传感器、WiFi、基站数据。点击"停止"后，hook 恢复透传真实数据。

> 建议先在 Melos 日志页确认 hook 已正常工作，再切换到微信使用小程序。

## 9. 采集自有场地数据（可选）

如果想在自己的场地上使用，需要先实地采集 GPS/WiFi/基站指纹：

1. 打开 Melos → 底部 Tab 2 "采集器"
2. 到达场地，点击"点采集"记录当前点（或开启"连续采集"自动记录）
3. 采集完成后导出 JSON 文件
4. 将采集数据放入项目，重新编译即可使用自定义场地

采集建议：
- 在场地周围均匀分布采集至少 20-30 个点
- 连续采集建议跑至少 2-3 圈，以便后续清洗和平均
- 采集时保持手机 GPS 精度在 10m 以内
- WiFi 和基站数据会自动在采集点同步记录

## 10. 常见问题

| 问题 | 解决方案 |
|------|---------|
| Magisk 安装模块后无法开机 | 开机时长按电源键 + 音量下 → recovery → 清除 Dalvik cache；或通过 fastboot 刷回原 boot.img 恢复 |
| LSPosed 管理器打不开 | 确认 Vector 模块在 Magisk 中已启用且重启过；尝试重新安装模块 |
| 模块勾选后微信闪退 | 检查 logcat 日志（`adb logcat \| grep Melos`），确认 hook 未报错 |
| 模拟启动后微信位置没变化 | 确认已在 LSPosed 中勾选微信并重启过；检查配置文件是否写入成功 |
| 被检测到 root | 确认 Shamiko 已启用白名单模式；确认微信不在 Shamiko 白名单中 |
| 编译报错 Kotlin 版本不兼容 | 确认使用 JDK 17，检查 `JAVA_HOME` 环境变量 |
| 配置文件写入失败 | 检查 `adb logcat \| grep MelosConfig` 日志；确认 Melos 已在 LSPosed 中勾选自身 |
