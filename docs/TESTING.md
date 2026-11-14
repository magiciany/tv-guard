# 实测记录（硬件/模拟器验证）

本文件记录 tv-guard v1.0 在 **BlueStacks 5（Android 13，arm64，1080×1920）** 上的端到端验证结果，
可作为「这个 App 真的能拦住」的证据参考。

- 验证时间：2026-11-14
- 被测包：`releases/tv-guard-v1.0-debug.apk`（scripts/build-manual.sh 构建，57 KB）
- 对照包：`releases/tv-guard-v1.0-debug-agp.apk`（Android Gradle Plugin 构建，71 KB）
- 测试目标 App：`com.apkpure.aegon`（APKPure，模拟「被守护的视频 App」）
- 验证间隔调成 **1 分钟**、答错锁定 **5 分钟**，便于快速触发

## 测试方法

```bash
ADB="/Applications/BlueStacks.app/Contents/MacOS/hd-adb -s 127.0.0.1:5555"

# 安装 + 授权 + 开无障碍
$ADB install -r releases/tv-guard-v1.0-debug.apk
$ADB shell pm grant com.hermes.tvguard android.permission.WRITE_SECURE_SETTINGS
$ADB shell settings put secure enabled_accessibility_services \
        com.hermes.tvguard/com.hermes.tvguard.GuardService
$ADB shell settings put secure accessibility_enabled 1
```

模拟遥控器按键（`input keyevent`）：

| keycode | 按键 |
|---|---|
| 19 / 20 / 21 / 22 | 上 / 下 / 左 / 右 |
| 66 | 回车（**比 23 DPAD_CENTER 更可靠**） |
| 4 / 3 | 返回 / Home |

## 结果

| # | 测试点 | 结果 | 证据 |
|---|---|---|---|
| 1 | 安装并覆盖旧版本 | ✅ | `Success`；`pm list packages` 有 `com.hermes.tvguard`，`lastUpdateTime` 更新 |
| 2 | 主界面渲染（11 项设置 + 底部提示） | ✅ | `docs/screenshots/01-main.png`，无重叠/截断，可滚动 |
| 3 | 无障碍服务绑定 | ✅ | `dumpsys accessibility`：`Bound services:{Service[label=守护答题 · 前台监视 …]}` |
| 4 | 识别目标 App 进入前台 | ✅ | 日志 `target foreground, accumulated=0s` |
| 5 | 到达间隔自动弹验证 | ✅ | 日志 `challenge fired after 60s`；顶层变为 `ChallengeActivity` |
| 6 | 验证界面渲染（词汇题） | ✅ | `docs/screenshots/02-challenge.png`：题干 `extend` + 4 个 2×2 选项 |
| 7 | 答对 → 放行并清零计时 | ✅ | 日志 `correct, timer reset`；挑战界面关闭，回到目标 App |
| 8 | 答错 → 锁定 + 显示正确答案 | ✅ | 日志 `wrong, locked 5 min, answer=extend = 延伸`；`docs/screenshots/03-locked.png` 显示「答错了」「请等待 4 分 57 秒」「正确答案：extend = 延伸」，4 个选项变灰显示「—」 |
| 9 | 锁定倒计时在走 | ✅ | 3 秒后同一界面由「请等待 4 分 57 秒」变为「4 分 54 秒」 |
| 10 | 离开目标 App 自动关闭验证 | ✅ | 日志 `candidate left -> …` → `target confirmed left`（连续 3 秒确认）→ `target not foreground, dismiss challenge` |
| 11 | 观看时长跨 App 不清零（防绕过） | ✅ | 切出再回来日志为 `accumulated=330s`（不是从 0 重新计） |
| 12 | 设置 4 位密码（二次确认） | ✅ | 键盘盲打 `1234` 两遍后返回主界面，按钮变为「密码：已设置（点击修改）」 |
| 13 | 密码校验退出守护 | ✅ | 日志 `guard stopped by user`；`settings get secure enabled_accessibility_services` 变为 `null` |
| 14 | 重新启用守护（自愈写回无障碍开关） | ✅ | 日志 `guard enabled, accessibility=true` + `service connected`，无障碍开关恢复为组件名 |
| 15 | 统计与操作日志 | ✅ | 界面显示「弹出 3 次 / 答对 1 / 答错 1」，与测试操作次数吻合 |
| 16 | 统计清零 | ✅（代码路径简单，未单独点） | `Prefs.resetStats()` |

## 未实测项

| 项目 | 说明 |
|---|---|
| 锁定倒计时到期后自动转回答题界面 | 需等满 5 分钟，测试时未等待完整周期（`GuardService.onTick()` 中 `state == STATE_LOCKED && now >= lockedUntil → STATE_CHALLENGE`） |
| 开机自启（`BootReceiver`） | 需要在模拟器/电视上重启系统验证 |
| 看门狗（`Watchdog`/`AlarmManager`）自愈 | 需杀掉服务进程后等待定时器触发 |
| 真实索尼电视 | 本次在 BlueStacks（Android 13）验证；电视端行为可能因系统版本（Android 7/9）略有差异 |

## 测试中发现的问题

### 1. `GuardService.INSTANCE` 偶发为 null（竞态，已确认为真实缺陷）

- **现象**：主界面自检行偶发显示「无障碍服务未连接（未开启，或被系统停止）」，但日志里服务是连上的。
- **原因**：`INSTANCE = this` 在 `onServiceConnected()` 第一行赋值，`INSTANCE = null` 在 `onDestroy()` 末尾。
  当系统"先创建新实例、后销毁旧实例"时，旧实例的 `onDestroy()` 会把新实例的引用清掉。
- **触发条件**：无障碍服务被反复重绑定（测试中每次 `uiautomator dump` 都会触发一次重绑定，日志里可见连续多条 `service connected`）。
- **影响**：仅影响「自检文案」和 `ChallengeActivity` 里依赖 `INSTANCE` 的几处调用；守护主流程（计时/拦截）不受影响。
- **修复建议**：`onDestroy()` 改为 `if (INSTANCE == this) INSTANCE = null;`

### 2. 测试方法上的坑（不是 App 的问题，但会误导测试）

- `uiautomator dump` 会让系统重绑定无障碍服务 → 状态被重置；且在弹窗类界面上常抓到下层 App 的窗口。
  本次改用 **截图 + 像素聚类定位控件**（按 `#1B2530`/`#2E73C9` 卡片色找按钮矩形）绕开。
- `screencap` 输出为 **1080×1920 竖屏**，与 `wm size` 报告的 1920×1080 不同，按后者算坐标会全部落空。
- `input keyevent 23`（DPAD_CENTER）对普通 `Button` 时好时坏，`66`（ENTER）稳定。
