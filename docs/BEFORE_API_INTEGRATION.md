# 接入真实 LLM API：进度与剩余事项

状态：**已完成 1–4**（版本 1.0.1）。第 5 项（参赛材料）需用户确认比赛要求。

当前实现：默认**本机分析**；配置端点并**明确同意**后才会上传；远程失败一律回落本地。

---

## ✅ 1. API Key 不进 App（已完成）

`ai/EngineSettings.kt` 只保存**中转端点 URL** 与可选的访问令牌；模型提供商的凭据保存在中转服务上。
设置页的说明也写明了这一点。

新增 `ai/RemoteLlmService.kt`：向中转 `POST` 三种任务（`state` / `actions` / `execute`），解析结构化响应。

**强制 https**：明文端点会被拒绝（debug 构建额外允许 RFC1918 私有地址，便于本地联调；
release 不引用该网络配置，始终 https-only）。

## ✅ 2. 隐私说明与同意（已完成）

原先硬编码的「本地 Mock 演示 · 无需 API Key · 不上传聊天内容」已删除 —— 接入远程后那句话就是假的。
`ui/components/Components.kt` 的 `EngineNote` 改为按**实际配置**显示：

| 状态 | 文案 |
| --- | --- |
| 未配置 | 本机分析 · 聊天内容不离开手机 |
| 已配置未同意 | 已配置分析服务，但尚未同意上传 · 当前仍在本机分析 |
| 已同意 | 上传分析 · 你主动提供的内容会发送至已配置的服务 |

`ui/settings/EngineSettingsScreen.kt`：配置端点、**独立弹窗**给出同意（写明将发送到的地址与内容范围）、
随时停止上传。**更换端点会清除旧同意** —— 用户同意的是某个具体端点。

**实现要点**：`canUseRemote = isConfigured && hasConsent` —— 两道闸门都满足才会上传。

## ✅ 3. 同步改异步（已完成）

三个引擎接口与 `ConversationRepository` 的方法加 `suspend`；`FlowViewModel` 与面板都新增 `busy` 状态，
按钮在调用期间禁用并显示「分析中…」。

解析仍同步：空输入与超长输入在**发请求之前**就被拒。

## ✅ 4. 输出结构约束与降级（已完成）

`RemoteLlmService` 逐字段解析，缺失或类型错误走 `JSONObject` 的可选读取；整体解析失败时
**回落本地引擎**，不会崩界面。

失败一律回落：未配置、离线、超时、非 2xx、JSON 不合规。

## ✅ 已验证（真机端到端，2026-09-19）

用本机 stub 服务（`.tools/stub-relay.py`，**非产品代码**）实测：

```
[stub] task=state   bytes=133 auth=True    ← 应用发来的请求，带 Bearer 令牌
[stub] task=actions bytes=155 auth=True
```

- 首页文案随配置切换为「上传分析 · 你主动提供的内容会发送至已配置的服务」
- 分析结果来自 stub（话题「决赛材料提交」、动作「确认演示视频负责人」）
- 清除配置后恢复「本机分析」，分析立刻返回本地结果，**日志中无任何网络记录**（完全未尝试联网）

## ⏳ 5. 参赛材料（待用户确认）

**已有的加分项**：四条入口（分享 / 划词 / 截屏分析 / 悬浮球），划词可"选中即就地分析"。

**现状**：142 项测试 0 失败，Lint 0 问题；APK 22.16 MB（仅 arm64-v8a）。

**注意事项**：演示截图不得含真人聊天（见 [上线前必读](BEFORE_PUSHING.md)）。

## 中转服务的响应格式

App 以 `POST` JSON 请求，`task` 决定期望的响应：

```jsonc
// 请求
{ "task": "state", "text": "老师：明天下午三点在 A203 开会…", "source": "TEXT" }
// 响应
{ "topic": "…", "stage": "DECISION", "goals": ["…"], "issues": ["…"],
  "agreements": [], "disagreements": [], "signals": ["…"], "facts": ["…"] }

// 请求 { "task": "actions", "topic": "…", "issues": [], "goals": [] }
// 响应
{ "actions": [ { "id": "a1", "title": "…", "description": "…",
                 "type": "CLARIFY", "reason": "…", "priority": 1 } ] }

// 请求 { "task": "execute", "text": "…", "topic": "…", "action": "…", "actionType": "…" }
// 响应
{ "replies": [ { "style": "自然", "text": "…" } ], "note": "…" }
```

`stage` 取值：`OPENING` / `DISCUSSION` / `NEGOTIATION` / `DECISION` / `FOLLOW_UP` / `CLOSING` / `UNKNOWN`。
`type` 取值见 `data/model/ActionType`。鉴权用 `Authorization: Bearer <访问令牌>`（可选）。

## 尚未做的

- **中转服务的参考实现**：尚未提供（可在需要时补一份几十行的 Node 或 Python 示例）
- **流式返回**：当前是「整段返回后显示」，未做逐字输出
- **失败重试**：超时后直接回落本地，未做退避重试
