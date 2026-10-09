package me.laumss.mosaic

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone


object MosaicStrings {
    private val zh: Boolean
        get() = Locale.getDefault().language.startsWith("zh")

    fun t(key: Key): String {
        val pair = STRINGS[key] ?: return key.name
        return if (zh) pair.first else pair.second
    }

    enum class Key {
        deleteCard,
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
        notes,
        notesEmpty,
        noteName,
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
        noteLinks,
        noteLinkFallback,
    }

    private val STRINGS: Map<Key, Pair<String, String>> = mapOf(
        Key.deleteCard to ("删除卡片" to "Delete Card"),
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
        Key.notes to ("笔记卡片" to "Note cards"),
        Key.notesEmpty to ("暂无笔记卡片" to "No note cards yet."),
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
        Key.noteLinks to ("已插入笔记" to "Inserted into notes"),
        Key.noteLinkFallback to ("笔记" to "Note"),
    )

    fun noteName(number: Int): String =
        if (zh) "笔记 $number" else "Note $number"

    
    fun noteLinkDetail(page: Int, updatedAtIso: String): String {
        val parts = ArrayList<String>(2)
        if (page >= 0) parts.add(if (zh) "第 ${page + 1} 页" else "Page ${page + 1}")
        val time = try {
            val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            iso.parse(updatedAtIso)?.let { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(it) }
        } catch (_: Throwable) {
            null
        }
        if (time != null) parts.add(if (zh) "$time 更新" else "updated $time")
        return parts.joinToString(" · ")
    }
}
