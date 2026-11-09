# 柔光影视 · 统一版（GlassBox-Unified）

> **版本 2.0.0-alpha**（versionCode 20）  
> **融合基线**：GlassBox 1.0.16 + TVBox OSC 源码快照 2026-11-09

把「GlassBox 的现代 UI / 播放内核」与「TVBox 的生产级源与直播/推送能力」合成一份可继续开发的工程。

---

## 融合原则

| 维度 | 策略 |
|------|------|
| **骨架** | GlassBox（Kotlin + Compose + 柔光玻璃） |
| **源引擎** | 保留 GlassBox 的 JSON / JS / PY / JAR，对齐 TVBox Spider 标准接口 |
| **播放** | MPV（首选）+ ExoPlayer + IJK；画面调节沿用 1.0.16 |
| **TVBox 迁入** | 直播模型、解析链、历史/收藏、局域网推送（能力抽取，UI 用 Compose 重做） |
| **不迁入** | TVBox 的 XML 多 Activity、XWalk、整包迅雷 P2P（P2 再评估） |

详细设计见：`docs/03_融合架构说明.md`  
GlassBox 1.0.16 完整变更日志已归档：`docs/GlassBox-1.0.16-CHANGELOG.md`

---

## 工程身份

| 项 | 值 |
|----|-----|
| 工程名 | GlassBox-Unified |
| 展示名 | 柔光影视·统一版 |
| applicationId | `com.glassbox.unified` |
| minSdk / targetSdk / compileSdk | 26 / 34 / 35 |
| 版本 | 2.0.0-alpha (20) |

与旧版 GlassBox（`com.glassbox.app`）及 TVBox 可**并存安装**。

---

## 目录要点

```
app/src/main/java/com/glassbox/app/
├── engine/
│   ├── *.kt              # 原 GlassBox 源引擎 + 三内核播放
│   ├── live/             # 【融合】直播频道 / EPG 模型（自 TVBox）
│   └── parse/            # 【融合】解析链模型（自 TVBox）
├── data/db/              # 【融合】历史 / 收藏（对齐 VodRecord / VodCollect）
├── server/               # 【融合】NanoHTTPD 局域网推送
├── ui/                   # Compose 全套界面（GlassBox）
└── core/                 # 网络、展示名等
```

文档：

- `docs/01_设计规范_柔光玻璃.md` — UI 规范
- `docs/02_源格式规范_JSON_JS_PY.md` — 源格式
- `docs/03_融合架构说明.md` — 本融合说明与迁移优先级

---

## 当前进度（P0 已完成）

- [x] 以 GlassBox 1.0.16 为可运行基线完整拷贝
- [x] 工程更名、applicationId、版本号
- [x] 融合架构文档
- [x] 直播 / 解析 / 历史收藏 / 推送 **包骨架与接口**
- [x] `GlassApp` 挂接融合模块（内存实现，保证能编译运行）
- [x] 依赖增加：NanoHTTPD、Room runtime

### P1（已完成 · alpha2）

- [x] 直播：M3U/TXT 解析 + Compose `LiveScreen`（设置 → 直播）
- [x] 解析链：`JsonParseExecutor` 接入 `preparePlayback` 兜底
- [x] 历史/收藏：SQLite 双写 + 启动迁移（对齐 TVBox 语义）
- [x] 推送：设置开关启停 NanoHTTPD（默认端口 9978）

### P2（可选）

- [ ] DLNA、本地文件、迅雷 P2P、完整弹幕

---

## 构建

```bash
# Windows
.\gradlew.bat :app:assembleDebug

# Linux / macOS
./gradlew :app:assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/`

> 说明：Chaquopy 的 `buildPython` 已改为注释，请按本机 Python 路径在 `app/build.gradle` 中配置，或依赖插件自动探测。

---

## 与两份原工程的关系

| 原工程 | 在本仓库中的角色 |
|--------|------------------|
| **GlassBox-1.0.16** | UI、播放、源引擎、设计规范的直接基线 |
| **TVBox OSC 快照** | 直播 / 解析 / 历史 / 推送 / DLNA 的能力来源（抽取后重写） |

原工程 ZIP 仍保留在附件中，便于对照实现细节，**不要**再往主路径拷贝 Java Activity。

---

## 开发约定

1. 新功能优先 Compose + 现有 `engine` / `data` 包。
2. 需要 TVBox 行为时，对照其源码写 Kotlin 实现，避免复制整文件进工程。
3. Spider 协议保持与四壳（TVBox / 影视仓 / OK影视 / PickTV）兼容。
4. 变更请同步更新 `docs/03_融合架构说明.md` 的进度表。
