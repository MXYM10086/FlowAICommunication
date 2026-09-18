# 阶段对照与路线

更新日期：2026-09-18。依据：[用户产品总纲](PRODUCT_VISION.md)。

## 产品定位

FlowAI Communication：Android 跨应用上下文感知 AI 沟通助手。

看懂沟通，想好怎么回，把聊天变成行动。三个产品模块为 Chat Insight、Reply Copilot、Chat to Action。

核心流程：`ContextCapsule → ConversationState → NextAction → ActionObject`。先理解沟通状态，再由用户选择下一步，最后生成回复或行动对象。

## 第一阶段与当前实现

| 范围 | 当前实现 | 状态 / 边界 |
| --- | --- | --- |
| Kotlin + Jetpack Compose Android App | com.flowai.communication；已有 Debug APK | 已构建，尚未真机验收 |
| 文本导入与 Message[] | 支持逐行、说话人标签和顺序 | 已实现；不支持所有聊天导出格式 |
| Conversation State | 展示话题、诉求、共识、分歧、未解决问题、信号、事实、阶段 | 两个 Demo 为固定 Mock；其他文本为通用模板 |
| Next Action | 展示 Top-3 及推荐理由，由用户选择 | 已实现 |
| Reply Copilot | 自然、简洁、正式候选回复，可编辑并复制 | 已实现；示例时间和内容需要用户核实 |
| Chat to Action | Demo B 输出一个组会 Event、两个 Task | 已实现草稿展示与复制；没有写入日历或待办 |
| Decision / Reminder | Decision 已定义并可展示；Reminder 尚无数据模型 | 未实现通用提取；Reminder 属于后续扩展 |
| 截图、悬浮、无障碍、分享 | 文本分享已接入（`text/*`、`EXTRA_HTML_TEXT`、`ClipData` 兜底）+ **划词入口 `ACTION_PROCESS_TEXT`**；截图/悬浮/无障碍仍为接口与 Stub | 图片分享与 OCR 未接入；无对应系统权限；真机微信链路待确认。V2 技术路线见 [V2_TECH_ROADMAP](V2_TECH_ROADMAP.md) |
| 最近分析 | 仅当前一次分析；ViewModel 内存保留，不使用 SavedStateHandle | 无历史记录，见下方上下文说明 |
| 核心测试 | 77 项单元测试通过（含 ShareConsumptionTest 11 项、SharedTextTest 31 项）；构建通过；Lint 0 错误 / 0 警告 | 2026-09-18 本轮重跑，见 TEST_RESULTS.md |
| 模拟器验收 | README 8 条验收路径在 API 34 模拟器上跑通并截图 | 旋转仅验证状态保留（未验横屏布局），分享未经过真实微信 IPC，见 TEST_RESULTS.md |
| 真机验收 | Android 16 / Redmi 上跑通 7 条自动 + 旋转人工确认 | 2026-09-19 首次真机；发现深色模式保持亮色、2.0x 字体顶栏换行两处问题，见 [DEVICE_ACCEPTANCE](verification/DEVICE_ACCEPTANCE.md) |
| 分享文本重复导入 | 已修复：任务重建时不再重新导入已消费的分享文本 | 仅存文本哈希不存原文，15 分钟过期；原文仍留在系统任务记录中，见 README 与 TEST_RESULTS.md |
| 微信分享导入 | 已修复：过滤器由 `text/plain` 放宽为 `text/*`，并支持 `EXTRA_HTML_TEXT` 与 `ClipData` 兜底 | 真机微信 IPC 交接仍需确认，失败可用 `adb logcat -s FlowAI` 定位 |
| 划词入口 | 已实现 `ACTION_PROCESS_TEXT`：任意 App 选中文字 → 工具栏 → FlowAI | 零权限零政策风险；但只覆盖可选文本控件，微信/QQ 消息气泡通常不可选，因此不能替代分享 |

## 上下文生命周期需要进一步落实

总纲要求 Just-in-Time Context：由用户主动触发，临时获取必要内容，完成后释放。

当前 MVP 已满足主动导入、没有后台抓取和没有网络上传。它只在 ViewModel 内保留当前这一次会话的聊天及结果，用于旋转屏幕时恢复；不使用 SavedStateHandle，也不保留最近分析历史。结束会话或返回首页会清空这些内容；进程重建后不恢复任何内容。旋转恢复是 Android 的配置变更保留机制，不等同于持久化；请在完成后主动结束。

**已知未闭合项**：进程被杀后任务被重建时，Android 会从任务记录重新投递原始 `SEND` intent。应用已通过 `ConsumedShareStore` 阻止"再次导入"（只存哈希、15 分钟过期），但**原文仍留在系统任务记录中**，直到用户把该任务从最近任务里划掉。这是 Just-in-Time Context "完成后释放"尚未完全落实的一点，若要彻底闭合需改用 `finishAndRemoveTask()`，代价是应用从最近任务中消失，需先做产品取舍。

跨应用入口开发前需要明确会话结束的时机，落实对输入、原文、解析消息、结果、最近记录和保存状态的统一清理。截图/OCR 的临时资源也应纳入生命周期。若保留历史，需明确由用户选择保存哪些内容。用户主动复制出的剪贴板内容属于独立输出，不应被当作已自动释放。

## 后续版本

| 阶段 | 用户总纲中的范围 | 当前状态 |
| --- | --- | --- |
| V1 | 聊天理解 + Next Action + Chat to Action | 第一阶段 Mock 流程已构建；模拟器与真机验收均已完成 |
| V2 | 悬浮助手、AI 输入法 | **已开始**：`CaptureSession` 会话生命周期 + 悬浮球原型（0.2.0）；文本分享与划词入口已接入；输入法、无障碍、主动截屏/OCR 未开发 |
| V3 | 自定义个人 Skill | 仅有 Skill / SkillRegistry 扩展边界 |
| V4 | 日历、待办、邮件、企业协作工具 | 未接入；当前 Task / Event 为草稿 |
| 长期 | 手机上的 Context-Aware AI Layer | 产品方向，不代表现有能力 |

真实 LLM / Vision、Room 等为总纲列出的后续技术选项，具体接入阶段尚未确定。第一阶段仍使用 MockLlmService；当前没有 AI 输入法、真实 OCR 或真实模型分析。ContextCapsule 的截图、参与者等扩展字段也需在相关入口阶段设计。

## 持续遵守的产品边界

- 用户主动调用与选择；最终发送由用户确认。
- 不修改或注入聊天客户端，不读取聊天数据库，不持续监听或长期录屏。
- 描述可观察的沟通状态，不输出心理断言或情绪百分比。
- 第一阶段不增加多模型、RAG、长期记忆、语音、产品内多 Agent、登录或数据库。

## 当前交付与下一步依据

源码、APK、文件树、运行步骤见 [README](../README.md)，已有自动验证结果与模拟器验收记录见 [TEST_RESULTS](../TEST_RESULTS.md)。

README 的 8 条验收路径已在 API 34 模拟器上自动跑通。剩余待补：真机（尤其横屏布局、真实微信分享链路、API 26 下限）人工确认。模拟器实测发现"分享会无条件清空进行中的会话"这一缺陷，需决定是否修正；同一条路径上的"分享文本被重复导入"与"微信 `text/html` 分享无法导入"均已修复。后续系统入口另按 V2 范围实施。
