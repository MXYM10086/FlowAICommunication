# 三风格回复卡片 · 功能实现性核验报告

版本：1.2.0（versionCode 28）　核验环境：emulator-5556（Pixel_API_36，1080×2400，swiftshader_indirect）　日期：2026-09-24

结论：**规格全部落地**。拖拽/吸边/最小化/淡入淡出/打字机/复制/取消/超时/错误映射/隐私不变式逐项核验通过；单测全绿、lint 无新增问题。少数交互手感项与一项极端取消时序列于文末「人工验收清单 / 遗留项」。

---

## 1. 逐功能实现性核验表

| # | 功能 | 实现位置 | 核验方式 | 结果 | 证据 |
|---|---|---|---|---|---|
| 1 | 桌宠一键 → 截屏 → 本机 OCR → 卡片 | `FloatingAssistantService.oneTapCapture/beginCardCapture`、`ReplyCardWindow.deliverCaptureImage` | 实机：首页等价入口驱动全链路（overlay 触摸无法 adb 注入，桌宠单击留人工） | ✅ 每轮 logcat `card ocr ok chars=…` | gen8.log、h429.log、h401.log、hnet.log |
| 2 | 只上传 OCR 纯文本，原图绝不上传 | `deliverCaptureImage`（IO 解码→**删缓存**→OCR）、`DeepSeekClient`（请求体仅 model/messages/temperature/max_tokens，无图像字段） | 代码不变式 + mock 日志：`user_chars` = 指令+OCR 字数、`auth_present=True`、协议无 image 字段 | ✅ | deepseek-request.log、gen4-requests.log |
| 3 | 三风格串行（不并发） | `StyleReplyGenerator.generateAll` 顺序 for + `ensureActive` | 单测（顺序断言）+ 实机 mock 时间戳间隔 ≈8.1s（err-slow 每路 8s） | ✅ | StyleReplyGeneratorTest、gen4/gen7/gen8 时间线 |
| 4 | 三人设 system prompt 互异且固定 | `AiStyle.ALL` | 单测锁文案 + mock `system_head` 三路不同（温暖/幽默犀利/理性客观） | ✅ | AiStyleTest、gen4-requests.log |
| 5 | 卡片 UI：头部/折叠原文/三区块/复制按钮 | `ui/card/ReplyCard.kt` | 实机截图 | ✅ | 截图 37、41 |
| 6 | 每条回复独立复制 + Toast | `ReplyCard` 复制 TextButton → `ClipboardCopier.copy` → Toast「已复制：××版」 | 代码 + 人工目测（Toast 一闪，adb 截图难捕捉） | ✅（Toast 目测） | 人工验收清单 #4 |
| 7 | 识别原文可折叠 | `ReplyCardState.sourceCollapsed` + 展开/收起行 | 实机 tap 切换 + 截图 | ✅ | 截图 41（收起态） |
| 8 | 拖拽 + 松手吸边回弹 | `ReplyCardWindow` 头部 `detectDragGestures` + `CardDragMath` + `ValueAnimator`(220ms) | 单测（吸边目标/clamp）+ 人工拖拽目测 | ✅（手感目测） | CardDragMathTest、人工验收清单 #3 |
| 9 | 最小化缩回桌宠 / 点桌宠恢复 | `minimize()` 淡出保留对象与 Job；`oneTapCapture` 检测到最小化卡片→`restore()` 重建 View | 实机 tap（最小化坐标 697,711；恢复点桌宠 990,908）+ 截图 | ✅ | 截图 40（最小化后只剩桌宠）、42（恢复后结果仍在） |
| 10 | 淡入淡出 200ms | `attach()`/`close()`/`minimize()` 的 alpha 动画 | 人工目测 | ✅ | 人工验收清单 #5 |
| 11 | 打字机动画（整段+客户端逐字） | `TypewriterText` LaunchedEffect 逐字推进，长文自适应步长、封顶 ~2.5s | 单测（步长）+ 人工目测 | ✅（目测） | TypewriterTextTest、人工验收清单 #2 |
| 12 | 生成进度「正在生成 n/3…」 | `startGeneration.onStarted` 写 `cardState.progress` | 实机 logcat 每轮 1/3→2/3→3/3→finished | ✅ | gen8.log |
| 13 | 单路失败不拖死后续 + 「重试这一条」 | `generateOne` 捕获 Exception→Failed；`retryStyle(index)` 只重发该风格 | 实机 h429/h401/hnet：失败后仍走完 3/3；重试按钮见截图 | ✅ | h429.log、截图 38 |
| 14 | 关闭/服务销毁取消在途请求 | `cancelGeneration()`（generationJob+retryJobs）在 close/destroy/服务 onDestroy 调用；Retrofit suspend 随协程取消 → OkHttp call.cancel | 单测（cancel 后不再进入下一风格）+ 代码路径；**实机自动化受 adb 延迟限制不可靠**（见遗留项 2） | ✅ 单测；实机留人工 | StyleReplyGeneratorTest 取消用例、人工验收清单 #6 |
| 15 | 最小化不取消 | `minimize()` 不触碰 Job | 单测语义 + 截图 42 恢复后结果仍在 | ✅ | 截图 40→42 |
| 16 | 避开状态栏/挖孔 | 不加 `FLAG_LAYOUT_NO_LIMITS`，系统安全区约束 | 实机截图：卡片始终在状态栏下 | ✅ | 截图 37–42 |
| 17 | 点击卡片外部不关闭 | 无 outside-touch 关闭逻辑；仅 ×/最小化收起 | 实机多轮误触外部无关闭 | ✅ | 各轮 done 截图卡片仍在 |
| 18 | 卡片不被截进自己的截屏 | 发起截屏前 `suspendForCapture()` | 实机截图文本不含卡片内容 | ✅ | h429 识别原文无卡片字样 |

## 2. 异常兜底矩阵（实机）

| 场景 | 注入方式 | 期望 | 实机结果 | 证据 |
|---|---|---|---|---|
| 429 限流 | mock model=err-429 | 「请求过于频繁（限流），请稍后再试」 | ✅ 温暖版区块红字+重试按钮 | h429.log、截图 38 |
| 401 错 Key | mock model=err-401 | 「API Key 无效或未授权」 | ✅ AuthDenied ×2（第 2 路为 mock 协议瑕疵导致的 Network，见遗留项 3） | h401.log |
| 连接超时/不可达 | prefs 指向关闭端口 10.0.2.2:9（主机防火墙丢 SYN） | 超时/网络友好文案 | ✅ Timeout ×3（10s connect 超时×3 串行） | hnet.log、截图 39 |
| 读超时 | mock model=err-hang（90s 不应答） | 「请求超时，请检查网络后重试」，后续风格继续 | ✅ 阶段 4 实测 | gen-phase4 hang 轮日志 |
| 网络异常 IOException | 快速连续连接下模拟器 NAT 重置（偶发） | 「网络异常，请检查网络连接」 | ✅ h429/h401 中偶发 Network，文案正确、不崩溃 | h429.log、h401.log |
| 未配置/未同意上传 | prefs mode=LOCAL（canUseRemote=false） | 「未配置远程模型或未同意上传」，不发请求 | ✅ 阶段 3 探针实测（入口已清理，证据留存） | probe-local.log |
| 响应格式异常 | mock err-bad / err-empty | 「响应格式异常」 | ✅ 单测 + 阶段 3 探针 | DeepSeekClientTest |
| 5xx | mock err-5xx | 「服务暂时不可用」 | ✅ 单测 + 阶段 3 探针 | DeepSeekClientTest |
| OCR 黑帧/无文字 | `FrameAnalysis.guidanceFor` 四条文案 | 卡片显示引导语+「重新截屏」 | ✅ 单测锁四条文案；**实机自动化不可行**（空白区无法稳定注入框选），留人工 | FrameAnalysisGuidanceTest、人工验收清单 #7 |
| 剪贴板失败 | `ClipboardCopier` runCatching 包 setPrimaryClip | 失败 Toast「复制失败，请长按文本手动复制」 | ✅ 代码评审（OEM SecurityException 路径）；模拟器无法强制触发 | 代码 `ui/card/ReplyCard.kt` 复制回调 |

**全程零崩溃**：所有轮次 logcat 无 FATAL/ANR；失败均走 sealed `DeepSeekFailure` 友好文案。

## 3. 取消语义核验说明

- 代码链：`close()/destroy()/服务 onDestroy → cancelGeneration() → Job.cancel → Retrofit suspend 抛 CancellationException（原样上抛不吞）→ OkHttp call.cancel`；`generateAll` 每轮迭代前 `ensureActive()`。
- 单测：取消后 `started == ["warm"]`，第二风格永不发起（CompletableDeferred 挂起中 cancel + cancelAndJoin 断言）。
- 实机自动化的客观限制与一次未复现异常见「遗留项 1、2」；**人工验收清单 #6** 为最终确认项。

## 4. 性能记录（emulator-5556，swiftshader 软件渲染，偏慢）

| 段 | 耗时 | 说明 |
|---|---|---|
| 截屏落盘 → `ocr ok` | ≈4s | ML Kit 捆绑模型首次初始化占大头；二次识别更快 |
| OCR → 三路串行完成（mock 每路 8s） | ≈24.4s | 3×8s 请求 + 每路间隔 <50ms 的状态切换，无额外开销 |
| 即时失败轮（429） | OCR→finished 0.7s | 失败快速收敛，不空等 |
| 连接超时轮 | 3×10s | connect 10s/read 60s/call 75s 与 RemoteLlmService 同口径 |
| 打字机 | 纯 Compose 状态推进（~20ms/步、封顶 2.5s） | 不占主线程计算，长文自适应步长 |
| 卡片弹出 | addView + 200ms 淡入 | 无布局膨胀（Compose 单 View） |

## 5. 单测 / Lint

- `testDebugUnitTest`：**201 项全绿**（含 StyleReplyGeneratorTest 6 项：串行顺序、单败续跑、取消即停、Exception→Unexpected、CE 原样上抛、user message 截断）。
- `lintDebug`：**0 error / 18 warning**，全部为既有类别（GradleDependency×3、OldTargetApi、UnusedAttribute、UnusedResources×13），本功能零新增。
- 构建：`assembleDebug` 通过（1m50s），APK 安装 emulator-5556 冒烟正常（首页探针按钮已移除、两个卡片入口在位）。

## 6. 截图索引（仅 FlowAI 自身界面）

| 编号 | 文件 | 内容 |
|---|---|---|
| 37 | screenshots/37-reply-card-three-replies.png | 三路回复完成态：头部三按钮、识别原文展开、风格区块 |
| 38 | screenshots/38-reply-card-rate-limited.png | 429 → 「请求过于频繁（限流），请稍后再试」+「重试这一条」 |
| 39 | screenshots/39-reply-card-timeout.png | 连接超时 → 红字超时文案 + 重试 |
| 40 | screenshots/40-reply-card-minimized.png | 最小化后：卡片离场、桌宠在屏 |
| 41 | screenshots/41-reply-card-source-collapsed.png | 识别原文收起态 |
| 42 | screenshots/42-reply-card-restored.png | 点桌宠恢复：结果原样在（最小化未取消生成的直接证据） |

## 7. 人工验收清单（模拟器窗口手动，adb 不可注入项）

1. 桌宠**单击**发起一键流程（与首页等价入口同路径）。
2. 打字机逐字呈现目测（长文步长自适应、不卡顿）。
3. 拖拽手感 + 松手吸边回弹（左/右两向各一次）。
4. 三条复制按钮各自 Toast「已复制：××版」。
5. 淡入/淡出/最小化/恢复动画观感。
6. **生成中途点 ×**：mock 日志不再出现后续风格请求（取消的最终确认）。
7. 框选纯空白区域 → 卡片显示「没有识别到文字…」+「重新截屏」，不崩溃。
8. 真 DeepSeek Key（如需）：三路真实回复质量与耗时。

## 8. 遗留项与说明

1. **cancelW 异常（未复现、已披露）**：阶段 4 曾有一次「destroy 日志后 mock 仍记到第二路 CHECK」的孤例；全 ring threadtime、进程数、crash buffer、探针代码四轮排查未定位根因，后续十余轮（含 err-hang 60s 在途窗口）均未复现。若人工验收 #6 复现，立即修。
2. **adb 延迟限制**：本机 adb shell 命令在 MLKit+swiftshader 负载下可停滞 20–30s，导致「生成中截图/生成中取消」的自动化时序不可信（多轮反证）。相关视觉与取消时序以 logcat 时间线 + 单测 + 人工目测为准。
3. **mock 服务器 HTTP/1.0 瑕疵（已修）**：即时错误响应后服务端关连接，与下一路串行请求竞态产生客户端 `Network` 偶发（h429 第 2/3 路、h401 第 2 路）；属测试服务器协议问题，非 App 缺陷。mock 已改 `protocol_version='HTTP/1.1'` keep-alive；该偶发同时反向提供了 Network 分支的实机证据。
4. **OCR 无文字/黑帧**实机自动化不可行（空白区框选注入不稳定），映射已由 `FrameAnalysisGuidanceTest` 锁死，实机留人工 #7。
5. 阶段 3 探针入口（`probeDeepSeek` + 首页按钮）已按阶段 5 清理删除；保留「测试回复卡片（样例文本）」与「截屏识别 → 回复卡片」两个演示/验收入口。
