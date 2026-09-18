# 推送到远程仓库之前必须执行

本仓库的开发过程中，有几张**含真实第三方界面**的截图一度被提交进历史，虽然已从当前提交移除，
但**仍存在于早期提交中**。它们是：

- `docs/verification/device/31-device-dialog.png` —— 真机的最近任务/应用列表
- `docs/verification/device/32-device-dialog2.png` —— 误触打开的计算器
- （更早已彻底清除：微信会话列表与朋友圈截图）

**当前仓库没有配置任何远程，这些内容没有被推送出去。** 但在 `git push` 之前，应当先把它们
从历史中移除，否则推送后无法收回。

## 清理方法

在仓库根目录执行（Windows PowerShell）：

```powershell
git filter-branch --force --index-filter `
  "git rm --cached --ignore-unmatch docs/verification/device/31-device-dialog.png docs/verification/device/32-device-dialog2.png" `
  --prune-empty --tag-name-filter cat -- --all

# 清掉备份引用与不可达对象
git for-each-ref --format="%(refname)" refs/original/ | ForEach-Object { git update-ref -d $_ }
git reflog expire --expire=now --all
git gc --prune=now --aggressive
```

## 验证

```powershell
git log --all --oneline --name-only | Select-String '31-device-dialog|32-device-dialog2'
```

没有输出即表示已彻底移除。

## 惯例

今后**不要提交含真实聊天、社交或应用列表界面的截图**。`.gitignore` 已加入相应规则，
但忽略规则只对未跟踪文件生效——**已经被提交过的文件必须用上面的方法从历史移除**。

验证用的截图应只包含本应用自身的界面，或在提交前裁剪掉第三方内容。
