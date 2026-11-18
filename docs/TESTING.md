# 验证记录

## v1.1 — 本地回归与 APK 验证（2026-11-18）

以下为构建时的本地检查，不等同于设备端端到端验收。随后完成的 BlueStacks 回归与 Sony 电视部署单独记录于后文。

| 检查 | 实际结果 |
|---|---|
| `python3 scripts/test-regression.py` | 3 个套件通过：9,000 道固定随机种子题目检查、29 项服务恢复/组件列表策略检查、23 项 PIN 冷却策略检查 |
| `python3 scripts/test-service-integration.py` | 46 项通过；运行实际 `GuardService`、`Prefs`、`PinStore`，Android 时钟、设置、SharedPreferences、生命周期由测试替身驱动 |
| `scripts/build-manual.sh --release` | 成功完成资源编译、Java 编译、DEX、对齐和签名；没有安装依赖 |
| `apksigner verify --verbose --print-certs` | APK 通过 v1/v2/v3 签名验证；与 v1.0 的证书 SHA-256 相同：`7786ab34f422575681816aa76740065cbfb46ff084e1569882860b4eba37c658` |
| `aapt2 dump badging` | `com.hermes.tvguard`，versionName `1.1`，versionCode `2`，minSdk `23`，targetSdk `33`；无 `application-debuggable` |
| `zipalign -c -v 4` | `Verification succesful` |

产物：`releases/tv-guard-v1.1-release.apk`，67,051 字节。SHA-256：`b4cf3e7a61393fc82e0d9d2670828c8aa8fee887826b4e2b5bc650fc9cf33487`。使用现有 debug keystore 签署非调试 release 包，仅供侧载。

### 集成覆盖范围

- 替换实例后销毁旧实例不再清除新 `INSTANCE`；累计观看、惩罚、答案与待答题状态恢复。
- 同次开机保持单调截止时间；模拟进程重建、开机标识变化与锁定到期；停止/重启保留暂停状态。
- 设置页暂停、重新观测前台才继续；后台内容事件不恢复观看；切换目标不继承旧目标状态。
- 连续播放无新窗口事件时，重绑定通过活动窗口根包名恢复；根节点稍后才可用时，下次 tick 重试；设置窗口为活动根时维持暂停。
- 实际无障碍设置写入保留其他服务及全局开关，写入返回失败会向调用者报告。
- 首次 PIN 设置不能覆盖已有 PIN，修改必须验证旧 PIN；错误次数跨上下文保留，第五次错误冷却 5 分钟，重启不能绕过。

### 本地检查的边界

测试替身不模拟 UI、系统调度、磁盘写入失败或 OEM 行为；设备端结果如下。

## v1.1 — BlueStacks 回归与电视部署（2026-11-18）

BlueStacks / Android 13 使用同一 SHA-256 的 release APK，目标为 APKPure，模拟受守护应用，不等同于真实电视视频播放。测试保留原 PIN 与应用数据，并恢复临时调整的配置。

| 场景 | 实际结果 |
|---|---|
| 1.0 → 1.1 覆盖升级 | 原目标、PIN、统计及首次安装时间保留 |
| PIN 配置授权/应用选择 | 取消不执行修改，验证后执行；后续操作仍需验证 |
| 设置页暂停/累计观看/服务重绑定 | 设置期间累计不增长；重新进入继续累计；目标仍在前台时重绑定后真实触发验证 |
| 真实答题与惩罚 | 答对清零放行；答错禁用选项并显示答案；实际等满 5 分钟后重新出题并答对放行 |
| 预览隔离/遥控器 | 预览答对、答错不改变真实计时与统计；Back 可退出测试；方向键和 Enter 验证通过 |
| 其他无障碍服务共存 | 正常启停守护保留另一个服务与全局开启状态 |
| 看门狗 | 不强停应用，仅移除自身组件，约 47.6 秒后自动恢复并重新拦截 |
| PIN 冷却 | 连错后关闭重开仍拒绝正确 PIN；实际等待 5 分钟后正确 PIN 恢复可用 |
| **强停后自动恢复** | **未通过**：系统强停移除了自身组件，重新打开主界面未自动连接，需要重新启用；随后原锁定剩余时间及答案恢复 |
| 崩溃检查 | 已收集的测试日志未见本应用 FATAL EXCEPTION / ANR；不等于覆盖所有运行路径 |

**Sony BRAVIA 4K 2015 / Android 7.0**：使用 `install -r` 覆盖部署同一 APK，读回 versionName 1.1 / versionCode 2。原 UID、首次安装时间、无障碍设置、悬浮权限及 WRITE_SECURE_SETTINGS 保留；ActivityManager 记录主界面已显示，日志确认服务连接并识别目标前台。启动等待命令曾返回 timeout，因此以独立的界面显示与服务日志交叉核验。未清除数据或更改 PIN/配置，未执行完整电视功能回归。

**仍未验证**：设备重启/开机自启、普通低内存回收后的自动恢复、PIN 冷却跨设备重启、真实目标切换后的状态重置、全部配置项逐项授权、PIN 修改全流程和统计清零；电视全部交互及 OEM 差异。强停不同于普通进程回收，不将手动重新启用算成自动恢复。

详细设备截图、原始日志及部署备份仅保存在本地，未随公共仓库发布。

---

## 历史 v1.0 — 硬件/模拟器验证

以下内容保留原始测试记录；其中描述的 v1.0 缺陷不代表当前 v1.1 仍未修复。

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
