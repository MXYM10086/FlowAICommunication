# 无障碍可读性 PoC 结论

日期：2026-09-19。设备：Redmi 23113RKC6C（vermeer），Android 16 / SDK 36，HyperOS V816。
目标：回答"能否用 AccessibilityService 读取微信聊天内容"——这决定 V2 的无障碍路线是否值得投入。

## 结论

**不能。** 微信不向无障碍框架暴露其界面内容。

因此按既定目标约束（"不触碰 Google Play 高危权限（无障碍）除非完成可读性 PoC"），**无障碍路线应当放弃**，不进入产品化。截屏 + 区域 OCR 才是"读取屏幕上对话"的可行路径。

## 验证方法

`uiautomator dump` 与真实 `AccessibilityService` 是**两套不同机制**，前者用 UiAutomation、后者是真正绑定的服务。因为微信可能只屏蔽前者（反自动化），所以不能只用 `uiautomator` 下结论。为此本项目**临时写了一个最小无障碍探针 App**（`.tools/a11yprobe`，安装在真机上，验证后已卸载、无障碍服务已关闭），它遍历 `rootInActiveWindow` 并记录节点数、文本数、控件类型与样本。

## 证据

| 实验 | 机制 | 结果 |
| --- | --- | --- |
| 微信会话列表 | `uiautomator dump` | **1 个节点、0 文本、0 content-desc**；该节点 `enabled="false"`、`bounds="[0,0][0,0]"` |
| 微信聊天详情页 | `uiautomator dump` | 同样 **1 个节点、0 文本** |
| **计算器（对照组）** | `uiautomator dump` | **89 个节点、16 条文本** |
| **计算器（对照组）** | **真实 AccessibilityService** | **160 个节点、31 条文本、42 个 content-desc、30 个 TextView** |
| **微信** | **真实 AccessibilityService** | **零事件**——服务完全收不到微信的任何无障碍事件，手动在微信内点击/返回也不产生事件 |

对照组的成功证明探针本身工作正常：同一服务、同一会话，计算器可读而微信完全不可读。这说明**不是探针配置问题，而是微信的行为**。

同时确认：微信的界面不是标准 `android.widget.*` 控件树（对照组计算器能列出 30 个 TextView，微信没有任何控件可枚举），与其自绘/自有渲染体系一致。

## 附带发现（与悬浮球相关，重要）

微信**声明并获得了 `android.permission.HIDE_OVERLAY_WINDOWS`**：

```text
android.permission.HIDE_OVERLAY_WINDOWS
android.permission.HIDE_OVERLAY_WINDOWS: granted=true
```

这正是 [V2_TECH_ROADMAP](V2_TECH_ROADMAP.md) 里预警过的最大产品风险，现在**从"推测"变成"已验证"**：微信位于前台时，其窗口上方的非系统覆盖窗口会被系统隐藏，**悬浮球在微信里不可见**。

## 对本产品的含义

1. **无障碍路线（路线图第 5 步）应关闭**。除技术不可行外，它在 Google Play 上也属高危权限（需论证"没有更窄的 API 可达到同样效果"，而"读取其他 App 内容"这一需求本身几乎无法论证）。
2. **悬浮球不能作为微信场景的入口**。用户开着微信时看不到悬浮球，因此**分享入口与划词入口是必需的，不是可选补充**。
3. **截屏 + 区域 OCR（路线图第 4 步）是唯一可行的"读取屏幕对话"路径**，且已实现并在双端验证通过。它虽然需要用户主动触发并每次授权，但这恰好符合 Just-in-Time Context 的产品原则。
4. 若未来仍想覆盖"读取整段微信对话"这一场景，可行的方向是**让用户主动提供内容**（分享多选消息／划词／截屏），而不是被动读取——这也与总纲"不读取聊天数据库、不持续监听"的边界一致。

## 复现方式

```powershell
# 探针源码在 .tools/a11yprobe（已 gitignore）
# 构建后安装并启用：
adb install -r .tools\a11yprobe\app\build\outputs\apk\debug\app-debug.apk
adb shell settings put secure enabled_accessibility_services com.probe.a11y/com.probe.a11y.TreeProbeService
adb shell settings put secure accessibility_enabled 1
# 切到目标应用后读探针输出：
adb logcat -s A11YPROBE
```

⚠️ HyperOS 会拦截 adb 安装（`INSTALL_FAILED_USER_RESTRICTED`），需先在开发者选项打开「USB 安装」。
