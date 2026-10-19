# Mosaic

Supernote 上的手写白板插件。React Native 是外壳，画面和交互都在 Kotlin 里。

Kotlin 代码在 `android/app/src/main/java/me/laumss/mosaic/`，按职责分包：

- 根包：React Native 入口（`MosaicEinkPackage`、各 `Mosaic*Module`）、宿主视图 `MosaicBoardView`、`MainApplication`。宿主按 `package` 声明扫描 ReactPackage，这些类留在根包。
- `board/`：交互控制器 `BoardInteractionController` 和它的各部分：选区 `SelectionController`、拖动缩放旋转 `SelectionTransforms`、整卡编辑 `CardCommands`、文字编辑 `CardTextEditing`、编辑卡片的笔画手势 `CardStrokeGestures`。
- `input/`：输入。笔 `PenInput`、手指 `TouchInput`、双指平移缩放 `PanZoomGesture`、工具 `ToolArbiter`，以及读取系统输入事件的 `InputReader`、`SliderReader` 等。
- `engine/`：场景数据和几何。`BoardEngine`（场景）、`BoardViewport`（视口）、`BoardHistory`（撤销）、`BoardGeometry`、`NeckGeometry`、`Shapes` 等，不依赖视图。
- `render/`：四层视图和绘制，见下。
- `drawpath/`：系统笔迹服务。连接和配置在 `DrawPathSession`，抬笔后的交接、背景同步和清轨迹的整屏刷新在 `InkHandoff`。
- `eink/`：刷新模式 `BoardPresentation`，套索的 FullUiAuto/Regal `LassoAutoRefresh`，移动后的清理 `CardTrailCleanup`（卡片：最终帧 UI Regal；画布平移缩放：`EinkTrailCleanup` 黑白清理）；`EinkRegionDriver` 读屏幕忙碌状态并提交黑白清理（JNI 在 `cpp/eink_region.c`，函数名带包名，换包要一起改）。
- `note/`：笔记卡片。打开关闭和页面状态在 `NoteSession`，文件读写在 `NoteController`、`TchFile`，插入 Note 的截图和链接在 `NoteShots`。
- `inkling/`：Notipal 协同。广播在 `InklingLink`，剪贴板、调色盘和导入在 `InklingBridge`，回调在 `InklingCallbacks`。

控制器的各部分都持有控制器（`board`），通过 `board.selection`、`board.pen` 这样的字段互相调用；外部（JS 模块、`MosaicBoardView`）也走 `controller.inkling` 这样的字段。`MosaicBoardView` 的各部分同理，用 `host.drawPath`、`host.inkHandoff`。

README 和代码不一致时，以代码为准。

## 结构

Kotlin 处理画面和输入，改动后把结果发给 JS。JS 负责保存和同步。读档或收到远程改动时，JS 把数据推回 Kotlin。

交互在 `BoardInteractionController.kt` 和 `board/`、`input/` 里的各部分，数据在 `BoardEngine.kt`，视口在 `BoardViewport.kt`，撤销在 `BoardHistory.kt`。

画面从下到上四层：`BoardContentView` 画内容，`TransientInkView` 画套索和图形的轨迹，`InteractionOverlayView` 画选框和手柄，`BoardChromeView` 画工具条和菜单。

JS 依赖的 `React/src/` 只有 `boardFormat.ts` 在仓库里，其余文件不在，JS 部分现在无法编译。项目没有测试。

## 工具条

从左到右是更多、笔、笔刷、马克笔、橡皮、套索、图形，最右边是关闭。代码在 `InkToolbar.kt`。

再点一次当前的笔会弹出粗细。图形按钮弹出直线、矩形、三角、椭圆。

点更多或从左边缘右滑，打开侧边菜单。菜单里有缩放、撤销重做、模板、存档、同步地址、笔记卡片列表、半透明开关。

左下角有一个触控开关，那块区域不能书写。

## 白板

缩放范围 10% 到 200%，默认 75%。双指捏合只在 75% 到 200% 之间有效，其余用菜单按钮。

模板有空白、点、线、十字四种，每种三档间距。白板和笔记卡片共用模板。

卡片分成几堆时，屏幕边缘会出现箭头，点一下跳到另一堆。

从顶部下拉关闭白板。

## 手写

每条笔迹属于画布、某张卡片或某条连接，由落笔位置决定。属于卡片或连接的笔迹跟着它移动。

笔尖下的笔迹由系统服务实时绘制，代码在 `DrawPathClient.kt`。抬笔后笔迹立即存入，但停笔 3 秒后才由 Mosaic 重绘替换。这 3 秒里内容层不能重画，否则系统笔迹会消失。逻辑在 `InkHandoff` 的 `holdInkHandoff` 附近。

`holdsSystemInk` 包括接触、待提交和整段 3 秒交接等待。悬停、排队的 rearm、重新 configure 和背景同步都遵守这个等待；不要在每次落笔强制重发笔配置。交接定时器不变，连续书写重新计时。

画框、拉出新卡片、连线、方括号扩卡这些手势不留笔迹。抬笔 0.6 秒后整屏刷新（插件根视图 `forceEinkFullUpdate`），系统笔迹就清掉了；背景同步（`syncBackground`）等刷新后 1.2 秒再发（`syncHoldUntil`）。书法笔（drawPath 类型 15）下设备实测：抬笔后 150ms 内刷新经常清不掉（先关掉再打开服务也不能稳定解决），0.6 秒和 1 秒后刷新每次都清掉；只同步背景、只重开服务、在原处重画再擦掉都清不掉。逻辑在 `InkHandoff` 的 `onPenTrailDiscarded` 和 `runPendingFullRefresh`。

套索和图形不走系统服务，轨迹由 Mosaic 自己画。

落在黑卡、黑色马克笔或实心图形上的笔迹自动变白。悬停时就会判断，代码在 `InkBackdrop.kt`。

## 刷新模式

进入套索工具或显示套索选区（包括长按卡片）后，LASSO 不设视图刷新模式，而是照系统 Note 套索（`AreaSelectionView`、`HandWriteClient.setFullAuto/setRegalStatus`）整段开启 FullUiAuto：`enableFullUiAuto(true, false)`，开关时都把 UI Regal（HWC 1020 `[9,1]`）打开。取消选区、结束拖动或自动工具回退不释放；手动选择其他工具时延迟 200ms 关闭；窗口失焦或隐藏时立即交还宿主，回来后重新打开；插件 detach 和手动全刷立即关闭。代码在 `LassoAutoRefresh`。

套索下的卡片拖动、改尺寸、旋转和纯笔迹变换（CARD_ADJUST、TRANSFORM）都不切换刷新模式：按下（开始拖动）时开 Regal，第一次真正移动时关 Regal，最终帧由 `CardTrailCleanup` 再请求一次 UI Regal。运动中卡片用黑白网点配方，松手恢复静态灰色。

画布双指平移缩放与 3154694 相同：PAN_ZOOM 用 A2 和扁平配方，结束后由 `CardTrailCleanup.schedule(region, …)` 在场景稳定后对旧位置露出的白色区域做黑白清理（`EinkTrailCleanup`、`CardCleanupBacklog`，JNI 在 `cpp/eink_region.c`）。笔记内部平移缩放、浮窗和滑条仍是 A2。

静态加深灰色配方只在 A2 之外显示；A2 期间卡片保持黑白网点，A2 释放时先恢复模式再画静态灰色（`BoardPresentation.State.bwCards`）。宿主全刷后的 EinkGrayRestore 保留原有实现。

## 笔刷

笔 10 档粗细。笔刷 12 档，跟随压感。马克笔一种粗细，黑、深灰、浅灰三色。档位在 `PenPopup.kt`。

图形也是笔迹。画完自动选中，可以转成正方形、圆、三种三角形，可以切换实心和空心。代码在 `Shapes.kt`。

## 手势

在画布上画矩形，终点停半秒再抬笔，生成卡片并收入框内笔迹。

在卡片边上画方括号，两条腿从边上伸出去，外侧一条横线，卡片朝那一侧加宽或加高，新区域里的画布笔迹收进卡片。腿可以伸进卡片，最多卡片该方向尺寸的 40%（24 到 72dp），也可以差 16dp 没碰到边。会撞上别的卡片时停在它前面。图片卡和笔记卡不行。没认出来时日志有 `[CardExtend] rejected` 和原因。代码在 `CardEdgeExtend.kt`。

从卡片起笔画到空白处，生成相连的新卡片。画到另一张同色卡片上，两张卡片之间生成一条线。

单指长按卡片拖动，松手时靠近同色卡片会吸附并连接。

双指平移缩放。双指双击隐藏或显示工具条。

侧边滑条上滑重做，下滑撤销。双指按住滑条或按住笔侧键，按系统设置临时切换橡皮或套索，代码在 `GestureSettings.kt`。

套索选中后旁边出现一列按钮，内容随选中对象变化。只选中手写时，可以识别成文字卡。

## 文字卡片

文字卡由文字和手写叠成。支持标题、粗体、斜体。文字变多时卡片长高，并推开下方卡片。代码在 `CardTextFormat.kt` 和 `CardTextEditor.kt`。

每张文字卡有字号倍数 `fontScale`（0.5 到 6），标题、正文和内边距一起缩放。拖边改卡片尺寸；拖角等比缩放卡片并同步改字号，换行位置不变。编辑文字时底部编辑条有缩小、放大字号两个按钮，按 `FONT_LEVELS` 逐档调整。手写识别成文字卡时，用手写各行的总长度对比同一段文字排成一行的宽度来估算字号，行高只作上限；卡片放在原来手写的位置。字号在 JS 里存为 `card.ext.fontScale`。Kotlin 和 JS 之间的场景协议版本是 9（`BoardEngine.PROTOCOL_VERSION` 和 `engineSync.ts`），两边要一起改。

卡片默认浅灰，可切换成黑卡。只有同色卡片能连接，换色后不同色的连接会断开。

选中后的按钮是删除、转成笔记、黑白切换、编辑文字。

## 连接

两张卡片之间的桥由 `NeckGeometry.kt` 计算。连接本身也能写字。

距离超过 160 桥消失，连接保留。重新靠近到 40 以内，桥再出现。

在连接上写字会锁定它，锁定成员仍一起选中。选中组合后，拖子卡片的边手柄独立调宽高，拖子卡片内部或长按它独立移动；浮动移动手柄和组合空白区域仍移动整组。`ConnectedCardConstraints` 同时约束所有当前可见的相邻桥：预览和提交都停在断桥距离之前，桥上已有的可见笔迹保持原位并完整留在桥的可用区域内，卡片边缘不能吞掉这些笔迹。尺寸到达内容极限后可通过受同样约束的单卡移动调整布局。桥在拖动中实时预览。子卡自身的文字/本地手写最小尺寸约束继续生效，笔记卡保留左右调宽并维持比例的规则。

连接子卡片的拖动和调宽高每帧只做一次约束检查（触摸帧合并到 `postOnAnimation`），桥上笔迹的预览路径每次手势只建一次。连接笔迹限制用局部坐标下四倍分辨率的 Region 遮罩，不能在主线程用 Path.op 合并压感笔迹；设备上真实书法笔轮廓曾使 Skia 曲线合并卡死并触发 ANR。场景锁只用于捕获引用，遮罩计算在锁外进行。

## 线

线不是连接，是一条特殊笔迹，space 是 `thread:<卡片A>|<卡片B>`。只能从一张卡片画到另一张同色卡片来生成。

形状由 `NeckGeometry.buildThread` 按两张卡片的位置算出，卡片移动时跟着变。笔迹里存的点只是创建时的形状。

线从两张卡片相对的边的中点出发，两端伸进卡片 12，画在卡片和桥下面，两头粗中间细。浅灰卡之间是阴影灰，黑卡之间是黑色。

线不能写字，套索选不中。橡皮可以擦掉线。删除卡片时它的线一起删除。

## 图片卡片

图片卡由图片和手写叠成，图片来自 Notipal。拖动角落等比缩放，不能换色。选中后只有删除。

## 笔记卡片

笔记卡片由文字卡转换而来，卡上的笔迹搬进笔记。白板上显示缩略图，笔点一下打开。

打开后是一页宽 480 的竖向长页。工具条的更多换成选取下方，撤销重做只能用滑条。工具条的关闭回到白板；从顶部下拉和白板上一样退出插件，下次打开直接回到这篇笔记。两种方式都会去掉大段空白、更新缩略图。

笔记里双指捏合在 70% 到 150% 之间缩放，100% 是页面铺满屏宽。双指手势先判断是平移还是缩放，之后只做这一种。100% 时只能上下滚动，松手时接近 100% 会吸回铺满。缩小后页面外是白色，页面四边有黑框，底边就在笔迹结尾处，页面左右不能移出屏幕。缩小时页面上下都能拖出黑框外，最多拖出页面在屏幕上高度的 30%（不超过屏幕高度的 30%），框外只是白色。框外不能书写：系统笔迹服务的禁写区包含页面外，笔迹划出页面会在边上截断，逻辑在 `NoteSession.noteOffPageRectsPx` 和 `PenInput.appendWritePoint`。双指双击隐藏或显示工具条时，停在顶部的页面跟着工具条边缘移动。代码在 `NoteSurface` 和 `PanZoomGesture.update`。

笔记文件存在 `notes/`，读写在 `TchFile.kt` 和 `NoteController.kt`。

## Notipal 协同

两边用广播通信，代码在 `InklingLink.kt`。代码里 Notipal 叫 Inkling。

交换目录是 `/sdcard/EXPORT/mosaic/`。Mosaic 把选中的笔迹实时写进 `clip_lasso.json`。Notipal 把图片放进 `inbox/`，Mosaic 每 0.8 秒检查一次。

Notipal 浮动工具条所在区域不能书写。笔粗细、图形、模板二级菜单或更多菜单打开时（`BoardChromeView.blocksPen`），状态广播带上 `chromeMenu`，Notipal 收起它的所有浮窗，关闭后再放回。导入只在白板上处理，笔记卡片里收到的内容等回到白板再处理。

在文档里选中文字后按 Mosaic 按钮，白板中央生成一张文字卡。

宿主是 Note 时，选中内容可以作为图片插入当前 Note。白板上会留下带笔记名的标记，点它回到那篇 Note。代码在 `noteBridge.ts` 和 `MosaicNoteShotModule.kt`。

## 保存和同步

白板存在插件目录的 `board.mosaic`，图片在 `images/`，笔记在 `notes/`。存档是 `/sdcard/EXPORT/mosaic/mosaic-backup.zip`。

菜单里填电脑地址走局域网，不填则创建在线房间。

插件关闭时，Kotlin 把白板场景另存一份到应用私有目录的 `board-scene.ops`，代码在 `SceneCache.kt`。下次打开时先画这份缓存，并立即恢复上次所在的笔记；JS 读完 `board.mosaic` 推来的场景随后替换它，期间白板上的笔先停用，最多 5 秒。JS 整体重载场景（`clearScene` 到 `documentReplaced`）在 `MosaicBoardEngineModule` 里合成一步执行，中间不会出现空白帧。

## 改代码时

对场景的改动通过控制器的 `applyChange()` 提交（各部分里写作 `board.applyChange(...)`），才能撤销和保存。新功能放进对应的部分，控制器只做分发和公共状态。JS 也要保存的，要在 `boardCommands.ts` 和 `App.tsx` 接上。

工具只存在 `ToolArbiter` 里，切换工具要经过控制器的 `applyTransition`，它会调用 `syncToolViews` 同步工具条、系统笔迹和刷新模式。选区、菜单这些也会影响能否书写，改动后要调用 `syncToolViews`。

界面文字在 `MosaicStrings.kt` 和 `i18n.ts` 两处。
