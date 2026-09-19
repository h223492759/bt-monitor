# 蓝牙监控 · bt-monitor

一个只做一件事的 Android 小工具：**长时间、无人值守地记录蓝牙（以及飞行模式 / Wi-Fi / 移动网络 / 屏幕 / 充电）的状态变化**，落成纯文本，便于事后用脚本分析。

它最初是为了回答一个具体问题：

> 荣耀 Magic7 Pro 的蓝牙会“自己关掉”。到底是**崩溃**还是**被关**？是**系统**问题还是**芯片**问题？

adb 只能盯几十分钟，手机一拿走就断。所以做成 App，能跟着手机跑一周。

---

## 一、它记录什么

| 类别 | 内容 |
|---|---|
| 蓝牙 | 适配器状态变化（OFF / TURNING_ON / ON / TURNING_OFF）、**本次 ON 已持续秒数**、**疑似崩溃次数**、本机蓝牙名 |
| 飞行模式 | 开 / 关 |
| Wi-Fi | Wi-Fi 开关状态、期望状态、SSID、RSSI |
| 移动网络 | 当前承载（WIFI / CELLULAR / NONE）、蜂窝是否可用、移动数据期望开关 |
| 其他 | 屏幕亮灭、充电插拔、开机、服务启停、网络可用性变化 |

采样分两类：

- **HB（周期采样）**——默认每 60 秒一行，把所有字段的快照写下来；
- **EV（状态变化）**——状态一变就写一行，不丢事件。

---

## 二、日志格式（关键，方便外部脚本解析）

文件按天切分，位于 App 外部私有目录：

```
/sdcard/Android/data/com.lhj.btmonitor/files/btlog/btmon-YYYY-MM-DD.txt
```

也可以在 App 里一键**导出 TXT 到「下载」目录**：

```
内部存储/Download/bt-monitor/btmon-export-YYYYMMDD-HHmm.txt
```

每行形如：

```
15:20:11.123 HB|src=tick|bt=ON|btSet=1|btUp=1699|btName=My Phone|ap=0|wifi=ENABLED|wifiSet=1|ssid=MyWiFi|rssi=-45|cell=1|net=WIFI|mdata=1|scr=OFF|batt=85|chg=Y|up=12345|crash=3
15:21:02.456 EV|bt_adapter|TURNING_ON->ON|btSet=1
15:21:02.460 EV|bt_ready|reached_ON|btName=My Phone
15:29:31.900 EV|SUSPECT_CRASH|ON->OFF 但系统期望仍为开(btSet=1)|crash=4
```

### 字段表

| 字段 | 含义 |
|---|---|
| `bt` | 蓝牙适配器态：`OFF` / `TURNING_ON` / `ON` / `TURNING_OFF` |
| `btSet` | 系统的“期望值”（读 `Settings.Global.bluetooth_on`）：1 = 想开，0 = 想关 |
| `btUp` | **本次进入 ON 后已持续秒数**；-1 表示当前不在 ON |
| `btName` | 本机蓝牙名（需要蓝牙权限，否则为 `-`） |
| `ap` | 飞行模式 1/0 |
| `wifi` / `wifiSet` | Wi-Fi 实际态 / 期望态 |
| `ssid` / `rssi` | 当前 Wi-Fi 名 / 信号（无定位权限时通常为 `?`） |
| `cell` / `net` | 蜂窝是否可用 1/0 · 当前承载 |
| `mdata` | 移动数据期望开关 1/0 |
| `scr` | 屏幕 ON / OFF |
| `batt` / `chg` | 电量百分比 / 是否在充电 |
| `up` | **监控服务自身已运行秒数** —— 若出现大跳跃，说明监控曾被杀/被冻结，那段数据不可信 |
| `crash` | 疑似崩溃累计次数 |

### 「疑似崩溃」是怎么判定的

```
蓝牙从 ON 掉到 OFF/TURNING_OFF   且   btSet(系统期望) 仍然 = 1
```

两条同时成立 ⇒ 不是人关的（人关会把 `btSet` 置 0），而是**协议栈自己崩了**。
这一条正是整件事的核心判据。

---

## 三、构建

### 方式 A：GitHub Actions（推荐，零本地环境）

推 tag 即出 APK，并自动创建 Release：

```bash
git tag v1.0.0
git push origin v1.0.0
```

也可以在 Actions 页面手动 `Run workflow`。

### 方式 B：本地构建

需要三样东西（都可用免安装 zip 版）：

| 工具 | 用途 | 官方下载 |
|---|---|---|
| JDK 17 | 编译 Kotlin / Gradle 运行 | https://adoptium.net/temurin/releases/?version=17&os=windows&arch=x64 |
| Android SDK Command-line Tools | 提供 `sdkmanager` 与平台包 | https://developer.android.com/studio#command-line-tools-only |
| Gradle 8.13 | 构建系统；也可用它 `gradle wrapper` 生成 wrapper | https://gradle.org/releases/ |

装完 SDK 后还需装平台包：

```bash
sdkmanager --install "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

然后在项目根目录写 `local.properties`：

```properties
sdk.dir=D\:\\AndroidDev\\sdk
```

构建：

```bash
gradle assembleRelease
```

> 本仓库**没有提交 gradle wrapper**（`gradlew` / `gradle-wrapper.jar`）——CI 用
> `gradle/actions/setup-gradle` 指定版本构建，本地用你自己装的 Gradle。
> 想要 wrapper 就在项目根目录跑一次 `gradle wrapper --gradle-version 8.13`。
> 注意：用 `gradle` 命令时项目根目录必须有 `local.properties`（见上）。

产物：`app/build/outputs/apk/release/app-release.apk`

> 本工程 release 复用 debug 签名（`signingConfig = debug`），目的是自用安装方便、无需管理 keystore。
> 如需正式签名，把 `app/build.gradle.kts` 里的 `signingConfig` 换成自己的即可。

---

## 四、装到手机后必须做的 4 步（否则跑不满一周）

MagicOS / EMUI 默认会清理后台：

1. **应用启动管理** → 找到「蓝牙监控」→ 关掉「自动管理」→ 允许自启动 / 关联启动 / 后台活动
2. **电池** → 应用省电策略 → 设为「不受限制」（或允许后台高耗电）
3. **最近任务** 里下拉卡片锁定（加锁），避免被一键清理
4. App 内点一次「申请忽略电池优化」并同意

装好后 App 会自动开始监控（默认 60 秒一采）。之后**看日志里的 `up` 字段**判断有没有断档。

---

## 五、目录结构

```
bt-monitor/
├─ app/src/main/
│  ├─ AndroidManifest.xml
│  ├─ java/com/lhj/btmonitor/
│  │  ├─ MainActivity.kt      界面：状态总览 / 开关 / 导出
│  │  ├─ MonitorService.kt    前台服务：周期采样 + 网络回调
│  │  ├─ EventReceiver.kt     静态广播：蓝牙/飞行/Wi-Fi/屏幕/电源
│  │  ├─ BootReceiver.kt      开机自启
│  │  ├─ Snap.kt              状态采集 + 文本行构造 + 崩溃判定
│  │  ├─ LogStore.kt          按天落盘 / 合并 / 清空
│  │  └─ ExportHelper.kt      导出到「下载」/ 系统分享
│  └─ res/                    图标、主题、strings、FileProvider 路径
└─ .github/workflows/build.yml
```

---

## 六、局限（写清楚，免得误导）

- **系统能杀服务**：厂商省电策略可能在长时间灭屏后冻结或杀掉本 App。日志里的 `up` 字段就是用来发现这件事的。
- **`ssid` 需要定位权限**，不给也能跑，只是 Wi-Fi 名记不到。
- **`mdata`（移动数据期望值）** 在部分 ROM 上读不到，会是 -1，不影响 `cell`（蜂窝是否可用）的判断。
- **无法区分“固件崩溃”与“驱动/固件层其他异常”** —— App 只能看到 Android 框架暴露的状态。要再往下结论，仍需 adb / tombstone。
