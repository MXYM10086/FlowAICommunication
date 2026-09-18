# 真机验收记录

验收日期：2026-09-19。验证设备：一台 Redmi 机型，**Android 16 / SDK 36**，HyperOS，arm64-v8a，1440x3200 @ 560dpi（带挖孔：顶部 138px）。
被测版本：`com.flowai.communication` **0.1.5**（versionCode 6）。
方式：`adb` 驱动真机，逐条操作并抓取界面层级。

> **真机截图不入库。** 真机截图会把手机自身的界面叠加进画面（框选界面、面板浮层都会露出下方真实内容），
> 因此可能包含聊天记录、应用列表、联系人昵称等。验收结论以下文文字记录为准。
> 仓库内只保留 `docs/verification/screenshots/`（模拟器截图，仅含 FlowAI 自身界面）。

> 这是**首次真机验收**。此前只有 API 34 模拟器验收，且模拟器有几项结构性不保真（见文末）。

## 环境差异（相对模拟器）

| 项 | 模拟器 | 本机真机 |
| --- | --- | --- |
| Android | 14 / SDK 34 | **16 / SDK 36** |
| 分辨率 | 1080x2400 @ 420 | 1440x3200 @ 560 |
| 挖孔 | 无 | 有（顶部 138px） |
| 厂商 ROM | AOSP | HyperOS |

applicationId 与 `targetSdk 34` 在 SDK 36 上安装运行正常。

## 逐条结果

| # | README 验收路径 | 真机结果 |
| --- | --- | --- |
| 1 | 首页 → Demo A → 分析当前沟通 | ✅ 话题"任务完成进度"、阶段"跟进"、缺明确完成时间、Top-3 三动作齐全 |
| 2 | 三种候选回复 + 复制；不同动作不同回复 | ✅ 自然/简洁/正式三条文案逐字匹配；点复制后出现"已复制到剪贴板" |
| 3 | Demo B → 提取任务与事件 | ✅ "1 个事件 · 2 个任务"；组会 明天 15:00 / A203；两个任务均 今晚 22:00；草稿未写入提示在位 |
| 4 | 生成会议事件仅展示事件；确认安排展示回复 | ✅ "1 个事件 · 0 个任务"、无任务卡片；确认安排三条回复含 明天15点 / A203 / 小王准备PPT |
| 5 | 其他聊天 → 通用 Mock、不套用 Demo | ✅ 话题"待确认的沟通话题"；**未出现** A203 / 小王 / 明天 15:00；提取动作给出空结果说明 |
| 6 | 空文本无法分析；标签后无内容报错 | ✅ 输入 `Alice:` → "请先输入有效的聊天内容"；空输入时页面存在 `enabled=false` 的分析按钮 |
| 7 | 旋转保留分析 | ✅ **人工确认通过**（adb 无法自动验证，见下） |
| 8 | 微信等应用分享 → 预填 | ✅ 分享入口在真机可用（`SEND`/`SEND_MULTIPLE` 均导入成功并显示"内容来自系统分享"） |

## 本机新增发现

### 1. 深色模式下应用保持亮色（已知取舍，非缺陷）

系统切到深色模式后界面**仍是亮色**，仅状态栏/导航栏跟随系统变深。原因：`ui/components/Components.kt` 的 `FlowTheme` 硬编码 `lightColorScheme`，未读取系统深色设置。

无崩溃、功能不受影响。第一阶段 Mock MVP 可以接受，但需明确"这是有意为之"还是待补。截图：`17-dark-mode.png`。

### 2. 系统字体放大到 2.0x 时顶栏标题换行（轻微布局问题）

`font_scale=2.0` 时 `TopAppBar` 的 "FlowAI" 断成两行（"Flow" / "AI"），与左侧"结束返回"按钮挤在一起。内容仍可读、可操作、无崩溃。截图：`19-font-2.0x.png`。

首页在 2.0x 下："粘贴聊天文本"主按钮（底部 y=2318）与 Demo A（y=3144）仍在 3200px 屏内；**Demo B 被推到屏幕下方需滚动**。

1.5x 与 2.0x 均无崩溃，文字未出现截断到不可读。

### 3. 横屏：人工确认通过，adb 无法自动验证

**结论：用户人工旋转后确认横屏没问题**（分析页在横屏下正常显示与保留）。

adb 侧无法自动验证，原因是这台 HyperOS 设备不响应：
- `accelerometer_rotation=0`、`user_rotation=0`，写入 `user_rotation 1` 后 `wm size` 仍为 `1440x3200`
- `dumpsys display` 的 `mCurrentOrientation` 始终为 0
- `wm set-user-rotation` 在此版本不存在

也就是说 adb 读到的方向与用户实际操作不一致（用户转屏后 adb 仍报 0），因此**横屏结论以人工确认为准，adb 的旋转读数在这台设备上不可信**。相关截图见 `15-landscape.png`、`15-portrait-back.png`。

### 4. 微信分享的真实载荷（本轮由真机日志定案）

从本机日志抓到的微信多选分享载荷：

```text
action    = android.intent.action.SEND_MULTIPLE
type      = message/rfc822          ← 不是 text/*
textClass = String, items=1
extras    = [sourcePackageName, android.intent.extra.SUBJECT,
             android.intent.extra.TEXT, android.intent.extra.STREAM]
```

微信把多选聊天消息当作**邮件**（`message/rfc822`）发送，文本仍放在 `EXTRA_TEXT`。此前按 MIME 族（`text/*`）判断，会直接拒绝该载荷 —— 表现为"微信里找到了 FlowAI，但点进去没反应"。已改为按"载荷是否真的含文本"判断（见 `SharedText.resolve`）。

## 未能覆盖 / 仍需人工确认

| 项 | 原因 |
| --- | --- |
| 真机微信端到端（手动选消息 → 分享 → FlowAI） | 需人工在微信内多选消息操作；应用侧已用真机日志抓到的载荷形状复现通过 |
| 挖孔避让在个别页面的表现 | 未逐页核对顶部 138px 挖孔区域是否遮挡内容 |
| 性能 / 内存 / 耗电 | 未测量 |
| API 26（minSdk 下限） | 未在低版本设备或镜像上验证 |
| 无障碍、悬浮窗、截屏 OCR | V2 范围，尚未实现 |

## 复现方式

```powershell
$adb = "$PWD\.tools\android-sdk\platform-tools\adb.exe"
$d = "<设备序列号>"   # 从 `adb devices` 获取

& $adb -s $d install -r ".\FlowAICommunication-debug.apk"
& $adb -s $d shell am start -n com.flowai.communication/.MainActivity

# 抓取 FlowAI 诊断日志（分享/划词入口的收到与拒绝原因）
& $adb -s $d logcat -s FlowAI
```

截图存于 `docs/verification/device/`。
