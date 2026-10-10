# Mosaic

Supernote 上的手写白板插件。React Native 是外壳，画面和交互都在 Kotlin 里。

Kotlin 代码在 `android/app/src/main/java/me/laumss/mosaic/`。

README 和代码不一致时，以代码为准。

## 结构

Kotlin 处理画面和输入，改动后把结果发给 JS。JS 负责保存和同步。读档或收到远程改动时，JS 把数据推回 Kotlin。

交互在 `BoardInteractionController.kt`，数据在 `BoardEngine.kt`，撤销在 `BoardHistory.kt`。

画面从下到上四层：`BoardContentView` 画内容，`TransientInkView` 画套索和图形的轨迹，`InteractionOverlayView` 画选框和手柄，`BoardChromeView` 画工具条和菜单。

JS 依赖的 `React/src/` 不在仓库里，JS 部分现在无法编译。项目没有测试。

## 工具条

从左到右是更多、笔、笔刷、马克笔、橡皮、套索、图形，最右边是关闭。代码在 `InkToolbarView.kt`。

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

笔尖下的笔迹由系统服务实时绘制，代码在 `DrawPathClient.kt`。抬笔后笔迹立即存入，但停笔 3 秒后才由 Mosaic 重绘替换。这 3 秒里内容层不能重画，否则系统笔迹会消失。逻辑在 `MosaicBoardView` 的 `holdInkHandoff` 附近。

套索和图形不走系统服务，轨迹由 Mosaic 自己画。

落在黑卡、黑色马克笔或实心图形上的笔迹自动变白。悬停时就会判断，代码在 `InkBackdrop.kt`。

## 笔刷

笔 10 档粗细。笔刷 12 档，跟随压感。马克笔一种粗细，黑、深灰、浅灰三色。档位在 `PenPopup.kt`。

图形也是笔迹。画完自动选中，可以转成正方形、圆、三种三角形，可以切换实心和空心。代码在 `Shapes.kt`。

## 手势

在画布上画矩形，终点停半秒再抬笔，生成卡片并收入框内笔迹。

从卡片起笔画到空白处，生成相连的新卡片。画到另一张同色卡片上，两张卡片相连。

单指长按卡片拖动，松手时靠近同色卡片会吸附并连接。

双指平移缩放。双指双击隐藏或显示工具条。

侧边滑条上滑重做，下滑撤销。双指按住滑条或按住笔侧键，按系统设置临时切换橡皮或套索，代码在 `GestureSettings.kt`。

套索选中后旁边出现一列按钮，内容随选中对象变化。只选中手写时，可以识别成文字卡。

## 文字卡片

文字卡由文字和手写叠成。支持标题、粗体、斜体。文字变多时卡片长高，并推开下方卡片。代码在 `CardTextFormat.kt` 和 `CardTextEditor.kt`。

卡片默认浅灰，可切换成黑卡。只有同色卡片能连接，换色后不同色的连接会断开。

选中后的按钮是删除、转成笔记、黑白切换、编辑文字。

## 连接

两张卡片之间的桥由 `NeckGeometry.kt` 计算。连接本身也能写字。

距离超过 160 桥消失，连接保留。重新靠近到 40 以内，桥再出现。

在连接上写字会锁定它。锁定后相连的卡片一起选中、一起移动。

## 图片卡片

图片卡由图片和手写叠成，图片来自 Notipal。拖动角落等比缩放，不能换色。选中后只有删除。

## 笔记卡片

笔记卡片由文字卡转换而来，卡上的笔迹搬进笔记。白板上显示缩略图，笔点一下打开。

打开后是一页宽 480 的竖向长页。工具条的更多换成选取下方，撤销重做只能用滑条。下拉回到白板，同时去掉大段空白、更新缩略图。

笔记文件存在 `notes/`，读写在 `TchFile.kt` 和 `NoteController.kt`。

## Notipal 协同

两边用广播通信，代码在 `InklingLink.kt`。代码里 Notipal 叫 Inkling。

交换目录是 `/sdcard/EXPORT/mosaic/`。Mosaic 把选中的笔迹实时写进 `clip_lasso.json`。Notipal 把图片放进 `inbox/`，Mosaic 每 0.8 秒检查一次。

Notipal 浮动工具条所在区域不能书写。导入只在白板上处理，笔记卡片里收到的内容等回到白板再处理。

在文档里选中文字后按 Mosaic 按钮，白板中央生成一张文字卡。

宿主是 Note 时，选中内容可以作为图片插入当前 Note。白板上会留下带笔记名的标记，点它回到那篇 Note。代码在 `noteBridge.ts` 和 `MosaicNoteShotModule.kt`。

## 保存和同步

白板存在插件目录的 `board.mosaic`，图片在 `images/`，笔记在 `notes/`。存档是 `/sdcard/EXPORT/mosaic/mosaic-backup.zip`。

菜单里填电脑地址走局域网，不填则创建在线房间。

## 改代码时

新改动在 `BoardInteractionController` 里通过 `apply()` 提交，才能撤销和保存。JS 也要保存的，要在 `boardCommands.ts` 和 `App.tsx` 接上。

切换工具后调用 `syncToolMirrors`，否则系统笔迹和界面状态会不一致。

界面文字在 `MosaicStrings.kt` 和 `i18n.ts` 两处。
