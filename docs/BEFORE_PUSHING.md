# 隐私清理记录（已完成）

## 状态：已完成，无需再操作

开发过程中有 5 张**含真实第三方界面**的截图一度被提交进 git 历史。**现已全部从历史中彻底移除。**

| 文件 | 内容 | 处理 |
| --- | --- | --- |
| `31-device-dialog.png` | 真机的最近任务/应用列表 | 已从历史移除 |
| `32-device-dialog2.png` | 误触打开的计算器 | 已从历史移除 |
| `37-wechat-foreground.png` | 微信界面 | 已从历史移除 |
| `38-wechat-page.png` | 微信会话列表 | 已从历史移除 |
| `43-device-bubble.png` | 微信朋友圈 | 已从历史移除 |

## 做了什么

```powershell
git filter-branch --force --index-filter `
  "git rm --cached --ignore-unmatch docs/verification/device/31-device-dialog.png docs/verification/device/32-device-dialog2.png" `
  --prune-empty --tag-name-filter cat -- --all
git for-each-ref --format="%(refname)" refs/original/ | ForEach-Object { git update-ref -d $_ }
git reflog expire --expire=now --all
git gc --prune=now --aggressive
```

前三个文件在此之前的提交里已用同样方式清除。

**注意**：历史被重写，因
**所有提交哈希都变了**。如果别处有基于旧哈希的引用（分支、克隆、CI），需要重新同步。本仓库没有远程，也没有其他克隆。

## 验证

```powershell
git log --all --oneline --name-only | Select-String '31-device-dialog|32-device-dialog2|37-wechat|38-wechat|43-device-bubble'
```

无输出，且逐项复查均为"已彻底清除"。重写后完整构建通过（126 项测试 0 失败，Lint 0 问题）。

## 回滚办法

重写前做了完整备份：

```
.tools/repo-backup-before-privacy-purge.bundle   (37.46 MB，已被 .gitignore 忽略)
```

```powershell
git clone .tools/repo-backup-before-privacy-purge.bundle <目标目录>
```

**该备份仍含隐私截图**，只在确需回滚时使用，用完应删除。

## 今后的惯例

不要提交含真实聊天、社交或应用列表界面的截图。`.gitignore` 已加入相应规则，但忽略规则只对未跟踪文件生效——**已被提交过的文件必须用上面的方法从历史移除**。

验证用的截图应只包含本应用自身的界面，或在提交前裁剪掉第三方内容。
