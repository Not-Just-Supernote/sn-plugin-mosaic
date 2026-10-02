package me.laumss.mosaic

import java.util.Locale


object MosaicStrings {
    private val zh: Boolean
        get() = Locale.getDefault().language.startsWith("zh")

    fun t(key: Key): String {
        val pair = STRINGS[key] ?: return key.name
        return if (zh) pair.first else pair.second
    }

    enum class Key {
        setWhiteboard,
        deleteWhiteboard,
        deleteCard,
        recognizeCard,
        convertToNote,
        accent,
        defaultCard,
        edit,
        sync,
        syncConnected,
        syncConnecting,
        syncOffline,
        syncConnect,
        syncDisconnect,
        syncAddressHint,
        translucent,
        sizeLevelHalf,
        sizeLevelDouble,
        sizeLevelQuad,
        whiteboard,
        whiteboardSwitcherEmpty,
        captureToNote,
        notes,
        notesEmpty,
        quickAccess,
        removeQuickAccess,
        noteName,
        whiteboardName,
        zoomOut,
        zoomIn,
        zoomDefault,
        thick,
        template,
        spacing,
        shape,
        noteHeaderPlaceholder,
        archiveSave,
        archiveLoad,
    }

    private val STRINGS: Map<Key, Pair<String, String>> = mapOf(
        Key.setWhiteboard to ("设白板" to "Set Board"),
        Key.deleteWhiteboard to ("删白板" to "Delete Board"),
        Key.deleteCard to ("删除卡片" to "Delete Card"),
        Key.recognizeCard to ("识别为文字卡" to "Recognize to card"),
        Key.convertToNote to ("转为笔记" to "Convert to note"),
        Key.accent to ("强调色" to "Accent"),
        Key.defaultCard to ("默认卡" to "Default"),
        Key.edit to ("编辑" to "Edit"),
        Key.sync to ("同步" to "Sync"),
        Key.syncConnected to ("已连接" to "Connected"),
        Key.syncConnecting to ("连接中…" to "Connecting…"),
        Key.syncOffline to ("连不上" to "Offline"),
        Key.syncConnect to ("同步" to "Sync"),
        Key.syncDisconnect to ("断开同步" to "Stop sync"),
        Key.syncAddressHint to ("电脑地址，如 192.168.1.5:3790" to "Computer address, e.g. 192.168.1.5:3790"),
        Key.translucent to ("半透明" to "Translucent"),
        Key.sizeLevelHalf to ("½ 邻卡" to "½ neighbor"),
        Key.sizeLevelDouble to ("2× 邻卡" to "2× neighbor"),
        Key.sizeLevelQuad to ("4× 邻卡" to "4× neighbor"),
        Key.whiteboard to ("白板" to "Whiteboards"),
        Key.whiteboardSwitcherEmpty to (
            "暂无白板，请先在画布上「设白板」" to
                "No whiteboards yet. Select “Set Board” on the canvas first."
        ),
        Key.captureToNote to ("截图插入笔记" to "Snapshot into note"),
        Key.notes to ("笔记卡片" to "Note cards"),
        Key.notesEmpty to ("暂无笔记卡片" to "No note cards yet."),
        Key.quickAccess to ("快速访问" to "Quick access"),
        Key.removeQuickAccess to ("移除快速访问" to "Remove quick access"),
        Key.zoomOut to ("缩小" to "Zoom out"),
        Key.zoomIn to ("放大" to "Zoom in"),
        Key.zoomDefault to ("缩放到 100%" to "Zoom to 100%"),
        Key.thick to ("粗细" to "Thick"),
        Key.template to ("模板" to "Template"),
        Key.spacing to ("间距" to "Spacing"),
        Key.shape to ("图形" to "Shape"),
        Key.noteHeaderPlaceholder to ("长按编辑标题" to "Long-press to edit title"),
        Key.archiveSave to ("保存" to "Save"),
        Key.archiveLoad to ("读取" to "Load"),
    )

    fun whiteboardName(number: Int): String =
        if (zh) "白板 $number" else "Whiteboard $number"

    fun noteName(number: Int): String =
        if (zh) "笔记 $number" else "Note $number"
}
