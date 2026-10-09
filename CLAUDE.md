# Mosaic 代码认知（CLAUDE.md）

Supernote E-ink 手写白板插件。宿主：Ratta 插件框架（`sn-plugin-lib`）。React Native 0.79 只是薄壳，**渲染、输入、交互、撤销全部在 Kotlin 原生层**。
`kt/` = `android/app/src/main/java/me/laumss/mosaic/`。

## 0. 仓库现状（改代码前先知道）
- `React/src/*`（boardFormat、types、cardGeometry、syncProtocol、sharedBoard、boardSyncClient、liquidEffects…）被 `App.tsx` 等引用，但**不在仓库里** → JS 侧无法编译/打包；`metro.config.js` 把它当 watchFolder。
- 源码注释已被剥离（到处是空行），命名和日志 TAG 是主要线索。
- 没用到的代码：根目录 `boardSyncClient.ts`、`liquidNecks.ts`（实际用的是 `React/src/boardSyncClient` 和 `kt/NeckGeometry.kt`）。
- 已写好但界面上看不到的功能：卡片强调色 `onToggleCardAccent`（#FFF9E3）、邻卡尺寸档 `computeSizeLevels`（½/2×/4×，`setMode` 会忽略这个参数）、菜单里的 lasso "编辑" 按钮（永久禁用）。
- 没有测试。构建：`buildPlugin.ps1`（Windows），只保留 arm64 架构。

## 1. 分层与数据流
```
index.js  注册插件按钮（NOTE/DOC 工具栏 id100；DOC 选中文字按钮 id101）
App.tsx   JS 持有文档模型 {cards, connections, ink, viewport}，负责持久化和同步
  ⇅ ① native→JS：事件 "MosaicBoardCommand" {ops:[…]}（boardCommands.ts 定义 BoardCommand 联合类型）
  ⇅ ② JS→native：MosaicBoardEngine.applyOps(base64 二进制，engineSync.ts，协议 v5)
MosaicBoardView（kt）  FrameLayout，4 层从下到上：
  BoardContentView（场景瓦片渲染） → TransientInkView（套索轨迹） → InteractionOverlayView（选框/预览/手柄） → BoardChromeView（工具条/菜单/弹层）
BoardInteractionController（4.5k 行）= 所有交互状态机
BoardEngine（单例）= 场景内存模型 + 空间网格 + 连接颈部(neck)；BoardHistory = 撤销栈（200 步）
```
- 原生层是交互的真相源：它先改 BoardEngine，再把 cardUpsert/strokeUpsert 等命令发给 JS；JS 写进模型，`scheduleSave` 800ms 防抖存 `board.mosaic`，再按 domain（structure/ink/document）推送同步。
- 远端同步或读档时走反方向：JS 改模型，再用 `EngineSyncSession` 做 diff，通过 applyOps 推给原生。
- JS 入口在 `App.tsx:582` 的 `applyCommands`；原生侧在 `kt/BoardInteractionController.kt:810` 的 `apply()`，它写入 history、发命令并做墨色修正（reconcile）。

## 2. 视觉层级（从上到下）
### 2.1 顶部工具条 `kt/InkToolbarView.kt`（类名 InkToolbar）+ `kt/BoardChromeView.kt`
- 白底横条，高约 83dp，内容可横向滚动；右上角单独放一个关闭 ×。
- 格子顺序：`More(⋮)` | 笔 | 笔刷 | 马克笔 | 橡皮 | 套索 | 图形 | 撤销 | 重做 | 手触开关。进入笔记卡片后，More 换成"选取下方"（图标复用 marker 字形）。
- 再点一次已选中的笔 → 弹出粗细面板。当前笔的格子带角标（粗细数值，马克笔显示 BK/DG/LG）。笔刷和马克笔用的是同一个 `tech` 图标。
- 图形格 → 弹出 4 个图形：直线、矩形、三角、椭圆。
- 视觉语言：只用纯黑 #111、白、灰 #555；描边 2dp；没有圆角。
- 双指双击可以隐藏/显示工具条；侧边菜单打开时，工具条也会被隐藏。
### 2.2 侧边菜单（代码里叫 switcher）`BoardChromeView.buildSwitcher:575`
- 两个入口：More 按钮；或从左边缘 ≤32dp 起手、横向拖动 ≥72dp。
- 菜单从上到下：缩放条（－ / 百分比 / ＋，点百分比回到 75%）、存档、读档、模板 | 同步地址输入框、连接按钮和状态 | 笔记卡片列表（缩略图；点击打开，长按定位） | 半透明开关、透明度滑条。
- 下方另有一块"笔记链接"面板，列出插入过宿主笔记的截图链接，点一项定位到对应区域。
### 2.3 白板本体
- 世界坐标单位 dp。缩放 10%–200%，100% 对应 scale 0.8，默认 75%（`BoardGeometry.kt:40`）。
- 双指缩放只在起始缩放 ≥100% 时生效，范围限制在 100–200%；低于 100% 时双指只能平移。其余档位走菜单按钮。松手时如果接近档位会吸附（±3%）。
- 背景模板 `BackgroundTemplate.kt`：样式有空白/点/线/十字，间距有窄/中/宽；白板和笔记卡片共用一套设置。
- 稀疏导航 `SparseNavigation.kt`：把卡片按 512 单位分块聚成"岛"。屏幕四边会出现圆形跳转钮（箭头 + 卡片数），点击居中跳到相邻的岛。
- 关闭：工具条 ×；或从顶部 y<120 下拉超过 180px。下拉的效果按所在层不同：在白板上是关闭插件，在笔记卡片里是回到白板。

## 3. 三个书写表面（核心心智模型）
每条笔迹都有 `space` 字段：
- `canvas`：画布，点存世界坐标。
- `card:<id>`：卡片，点存卡片局部坐标，随卡片一起移动和裁剪。
- `connection:<id>`：连接颈部，随连接走。**写在连接上会把该连接自动设为 locked**（`BoardEngine.kt:463`）。

落笔时用 `topCardAt` 判断归属：先看卡片，再看连接，都不是就落在画布。
`BoardEngine.CardRec` 的字段：kind = text | image | note、content(markdown)、imagePath、noteRef、bgColor/textColor（非空且不是白色 ⇒ `colored`，即黑卡）、title。

## 4. 手写管线
- 低延迟笔迹不是自己画的：通过 binder 调用系统 drawPath 服务（`DrawPathClient.kt`，`android.demo.IMyService`）画硬件尾迹。传过去的参数有笔型、宽度、颜色、禁写区（工具条、Notipal 浮层）。
- App 先从 evdev 原始输入读到压力（`InputReader.kt`），抬笔时自己生成 StrokeRec 并提交（`commitStroke:1487`），然后 `syncBackground` 把系统尾迹换成 App 自己渲染的结果。
- 悬停进入或悬停期间每 1 秒会重新武装（rearm）drawPath，好让笔色和笔型及时生效。
- 套索和图形模式下会关掉 drawPath，改由 TransientInkView 画轨迹。
- 渲染：512 世界单位一块瓦片，后台线程光栅化，缓存 64MB（`BoardContentView`）。
- E-ink 刷新模式：`MosaicEinkRefreshModule` 提供 DEFAULT(7)、A2(4)、DTH(5)，按 owner 申请和释放。

## 5. 笔刷与 graphx
- 笔种 `PenStyle`：PEN（README 叫针管笔，10 档 0.3–2.0，默认 0.3）、BRUSH（压感，12 档 0.1–2.0，默认 0.5）、MARKER（固定 3.0，三种灰：黑/深灰/浅灰）、FILLED_SHAPE（内部用，实心图形）。档位定义在 `PenPopup.kt`。
- graphx 也是笔迹：`isShape` 由点数推断（矩形 5 点、椭圆 65 点），代码在 `Shapes.kt`。
  - 绘制：选好图形后拖拽，最短 8dp。画完自动选中，并切到套索覆盖（`finalizeShape:2047`）。
  - 选中后按钮：矩形 → 正方形；椭圆 → 圆；三角 → 等腰、等边、直角；另有填充/空心切换。
  - 选中后手柄：边手柄缩放，旋转手柄（吸附 90° ±3°）。

## 6. 输入与手势（`kt/InputReader.kt` 读 evdev → `InputRouter` → controller）
- 框架层的手指事件全部吞掉（`MosaicBoardView.onTouchEvent`），手指输入只认 evdev。
- 仲裁：笔接触、笔悬停、笔离开后 300ms 内，手指都让位于笔（`InputArbiter`）。
- 工具来源叠加 `ToolArbiter`：一个 base 工具（工具条切换），上面压多层 override。override 来源有 PEN_BUTTON、SCREEN、SLIDEBAR、SHAPE；各层各自决定在抬笔时还是下次落笔时退出。
- 系统设置由 `GestureSettings.kt` 读取：笔侧键、双指屏幕手势、侧滑条手势分别映射到橡皮/套索/关闭；还有左右手、校准值。

| 输入 | 行为 | 代码位置 |
|---|---|---|
| 笔在画布/卡片/连接上写 | 写入对应表面 | `penDown:1169` |
| 在画布上画近似矩形，在终点停笔 500ms | 出现预览框；抬笔即建卡，并收走框内 ≥70% 点数的画布笔迹 | `updateFrameGesture:1570` `CardFrameRecognizer` |
| 从卡片内起笔，到画布上收笔 | 在收笔方向新建同色卡片并连上（间距 72） | `completeCardStroke:1637` |
| 从卡片内起笔，到另一张卡上收笔 | 两卡同色且间距 ≤160 才连接 | 同上 |
| 笔或手指点笔记卡片 | 打开笔记卡片 | `PenSession.OpenNote` |
| 单指长按卡片（600ms；扁平态 400ms；已有选区 100ms） | 选中它所在的 locked 连通组并拖动；松手时吸附到 40 以内的同色卡，并自动建立连接 | `fingerDown:2930` `settleCardDrop:2823` |
| 双指 | 平移/缩放；如果配置了屏幕手势且两指同时按下（300ms 内判定），可能变成临时橡皮/套索（`TwoFingerToolGuard` 按笔倾角扇区判断） | `beginPanZoom:3540` |
| 双指双击 | 隐藏/显示工具条 | `onTwoFingerTap:2916` |
| 左边缘右滑 | 打开菜单 | EdgeSwipe |
| 侧滑条上滑/下滑 | 重做/撤销 | `onSlider:3769` |
| 侧滑条双指：按住 / 轻点 | 按住 = 临时工具（正在写的那一笔从当前点起转为橡皮）；轻点 = 切换 base 工具 | 同上 |
| 笔侧键或笔尾橡皮 | override；笔离屏时按下会预武装（armed）橡皮 | `onPenState:3700` |
| 宿主刷新键（KEY 173） | 退出所有工具和选区，全屏重绘 | `onManualRefresh:3756` |

## 7. 选区（套索）与选区动作
- 套索框选：完全包含的卡片 + 完全包含的笔迹。如果什么都没框中，退而选部分相交的笔迹；还没有，再退而选相交的卡片（`selectInRect:1742`）。
- 选区动作按钮（52dp 方块）贴在选框右侧，放不下就放到下方（`updateLassoOverlay:1890`）：

| 选中内容 | 动作 |
|---|---|
| 单张文字卡 | 删除、转笔记、黑白切换、编辑文字 |
| 单张图片卡 | 删除 |
| 单张笔记卡 | 删除、编辑标题 |
| 图形 | 删除 + 图形变换 |
| 纯画布墨迹 | 删除、识别成文字卡（走宿主 OCR：`recognizeLasso.ts`） |

  另外，宿主是 Note 时，所有选区都会额外出现"插入笔记"（shot）。
- 手柄：单卡只有四边中点（图片卡加三个角，笔记卡只有左右）；多选是三个角 + 旋转手柄。

## 8. 卡片
- 外观（`BoardContentView.drawLiquidAndCards:1310`）：
  - 默认卡是**浅灰 #D0D0D0** 填充 + 黑色剪影投影（偏移 5）、直角。README 说的"白卡"在代码里是灰卡。
  - 黑卡是 #000 填充，文字和墨迹为白。
- 文字卡：Markdown 子集，支持 `#`/`##`/`###`、`**粗**`、`*斜*`、`> ` 整行反色高亮、缩进（`CardTextFormat.kt`）。
  - 编辑走 `CardTextEditor` 浮层，带键盘和格式按钮（标题/粗体…）。
  - 卡片长高时，会把同列下方的卡片整体下推（`pushCardsBelow`）。
  - 默认尺寸 320×280，最小 80，最大 2000。
- 图片卡：Notipal 把图片放进 `/sdcard/EXPORT/mosaic/inbox`，JS 每 800ms 轮询认领（`mosaicImportBridge.ts`），复制到插件目录 `images/`，在视口中央建卡并选中。
  - 图片按 fit 绘制；卡片上可以写字。
  - 拖角等比缩放，最小 240。
- 笔记卡片（`openNote:223`、`closeNote:253`）：
  - 只能由文字卡"转笔记"得到：卡上的墨迹移进笔记，文字前两行变成标题和副标题（`onConvertCardToNote:4007`）。
  - 文件：`notes/<ref>.mnote`（Tch 格式）+ 同名 `.png` 预览。
  - 打开后，整张场景替换成宽 480 的纵向滚动文档：白板快照和 history 先挂起，不显示菜单，More 换成"选取下方"。在这一层，单指不滚动，双指只能纵向平移，缩放锁定。
  - 关闭时压缩大段空白（>160 收成 64），刷新预览图，卡片高度按内容重算，并下推下方卡片。
  - 关闭插件时会记住当前打开的笔记，下次启动自动恢复。

## 9. 连接（几何桥 / "液态颈部"）
- 只能连同色卡（`canConnect`）。颈部是 `NeckGeometry.build` 生成的 Path，填充色和卡片一致。
- 卡片间距 >160 时颈部不画；已经断开的连接，要靠近到 ≤40 才会重新出现。**连接记录本身一直保留**，只有显示随距离变化（`BoardEngine.buildNeckLocked:672`）。
- locked 连接（写过字就会被锁）：组内卡片一起被选中、一起移动，单卡不能单独缩放。
- 切换卡片黑白时，会断开不再同色的连接，并自动连上 40 以内的同色卡（`reconcileConnectionsForColor`）。

## 10. 墨色自适应与 E-ink 呈现
- 笔迹颜色在提交时决定（`InkBackdrop.resolve`）：落在黑卡上、或压在黑色马克笔上 → 白墨，否则黑墨。马克笔和实心图形的颜色不受影响。
  - 改了卡片颜色或移动了马克笔之后，`InkBackdrop.reconcile` 会重算相关笔迹。
- 悬停预判：`trailWantsWhite:1133` 根据笔尖下方是什么，提前切换系统尾迹颜色。判断带 6dp 迟滞，避免在边界附近来回闪。
- `BoardPresentation`：拖动、平移、缩放、变换时进入"扁平态"——卡片画成纯色块并描边，隐藏模板；平移缩放时用 A2 刷新模式并把卡片临时画黑；结束后停 600ms 再恢复正常显示，并做一次刷新复位。
- 半透明模式：白板叠在宿主页面上，雾化程度 15–40%（`TranslucentStore`）。

## 11. Notipal（代码里叫 Inkling）协同 `kt/InklingLink.kt`
- 通信方式是同进程广播 `me.laumss.mosaic.*`。
  - Mosaic → Notipal：白板状态（是否可见；当前在 board 还是 note 层）。
  - Notipal → Mosaic：它的浮动工具条位置和浮层位置（这些区域变成禁写区，指针事件也被吞掉）。
  - Notipal 发来的指令：close、paste_strokes、clear/delete_selection、text_card、inbox。
- 剪贴板文件放在 `/sdcard/EXPORT/mosaic/`：`clip_lasso.json`（当前选区笔迹实时导出）、`paste_strokes.json`、`inbox/`。
- 所有导入只在白板层生效；处于笔记层时一律延后处理。
- DOC 中选中文字：宿主按钮 101 → `docSelectionBridge.ts` → 在视口中央建一张文字卡（`TextReflow` 先重排）。
- 插入宿主笔记（NoteShot）：把选区渲染成 PNG，作为图片元素插入当前 Note 页（`noteBridge.ts`），并记录到 `note-shots.json`。白板上对应区域会画角标和标签（`NoteLinks.kt`）。从宿主再次进入时，会定位到这块区域；关闭时刷新笔记里的那张图片。

## 12. 持久化与同步
- 插件目录里的文件：`board.mosaic`（二进制 BoardDoc）、`images/`、`notes/`、`sync-config.json`。
- SharedPreferences：视口、当前笔记 ref、模板、透明度。
- 存档：`/sdcard/EXPORT/mosaic/mosaic-backup.zip`（`archiveStore.ts` 调用 `MosaicArchiveModule`）。
- 同步（`App.tsx:1042`）：菜单里填地址就连局域网伴侣页面；不填地址则创建在线房间，并弹出分享链接。
  - 协议：WebSocket，用版本号（revision）协调；图片走 HTTP（`assetSync.ts`），底层 socket 由 `rawNet.ts` 调原生模块。
  - 远端整份导入时会弹框确认。

## 13. README 与代码不一致之处（以代码为准）
| README | 代码 |
|---|---|
| 双指可在 75–150% 自由缩放 | 只有起始缩放 ≥100% 才能捏合缩放，范围 100–200%；低于 100% 只能平移 |
| 矩形闭合即建卡 | 画完要在终点停笔 500ms 识别成功，才会建卡 |
| 白卡 / 白色连接 | 实际是浅灰 #D0D0D0 |
| 3 种模板 | 4 种样式 × 3 种间距 |
| 长按卡片用键盘编辑 | 长按 = 选中并拖动；编辑要点选区动作里的"编辑文字" |
| 拉远断开时清掉连接上的笔迹 | 连接记录保留，只是不显示颈部；连接上写过字就会被锁定，锁定组本来就拉不开 |
| 可以按邻卡尺寸统一缩放 | 逻辑写好了，但界面上没有入口 |

## 14. 改动指引
- 工具条或菜单的外观/入口：`InkToolbarView.kt`、`BoardChromeView.kt`、`ToolIcons.kt`（全部手绘 Canvas 图标）。
- 文案有两份，改时要同步：`MosaicStrings.kt`（原生）和 `i18n.ts`（JS）。
- 交互行为：`BoardInteractionController.kt`（按上面的行号找）；几何常量在 `BoardGeometry.kt`。
- 卡片和连接的绘制：`BoardContentView.kt`、`NeckGeometry.kt`；选框和手柄：`InteractionOverlayView.kt`。
- 新增一种场景变更：
  1. 在 controller 里构造 `BoardHistory.Change`，交给 `apply(record=true)`。
  2. 如果 JS 需要持久化，要在 `BoardCommandEmitter` 和 `boardCommands.ts`、`App.applyCommands` 两头都加上。
- 动了笔或工具状态以后，要经过 `syncToolMirrors` 刷新 `host.inkEnabled/lassoEnabled`，否则 drawPath 和界面状态会不一致。
- 设计时注意 E-ink 约束：反馈尽量用黑白高对比和块面；动效要避免频繁刷新；拖动期间显示简化版本（扁平态）。
