package me.laumss.mosaic

import android.content.Context
import android.net.Uri
import android.provider.Settings
import android.util.Log


object GestureSettings {

    private const val TAG = "MosaicSettings"
    private const val HOST_EMPTY_SCREEN_GESTURE = "1"
    private const val HOST_EMPTY_SLIDEBAR_GESTURE = "0"

    enum class GestureTool { ERASER, LASSO, OFF }

    class Resolved(
        
        val screenGesture: GestureTool,
        
        val slidebarGesture: GestureTool,
        
        val penButton: GestureTool,
        val leftHand: Boolean,
        
        val sliderSide: Int,
        
        val calibX: Int,
        val calibY: Int,
        val raw: Map<String, String>,
        val sources: Map<String, String>,
    ) {
        fun describe(): String =
            "screen=$screenGesture slidebar=$slidebarGesture penButton=$penButton hand=${if (leftHand) "left" else "right"} sliderSide=$sliderSide calib=($calibX,$calibY)"

        companion object {
            val DEFAULT = Resolved(
                screenGesture = GestureTool.LASSO,
                slidebarGesture = GestureTool.ERASER,
                penButton = GestureTool.ERASER,
                leftHand = false,
                sliderSide = 1,
                calibX = 0,
                calibY = 0,
                raw = emptyMap(),
                sources = emptyMap(),
            )
        }
    }

    private val KEYS = arrayOf(
        "gesture_preference_screen",
        "gesture_preference_slidebar",
        "lamy_button",
        "electronic_button",
        "hand_dominance",
        "writing_posture",
        "eraser_recognition_type",
        "diffX",
        "diffY",
    )

    @Volatile
    private var lastResolved: Resolved? = null

    fun readCached(): Resolved? = lastResolved

    private val PROVIDER_TABLES = arrayOf(
        "varchar", "int", "integer", "long", "float", "boolean", "string", "text", "settings",
    )

    fun read(context: Context): Resolved {
        val raw = LinkedHashMap<String, String>()
        val sources = LinkedHashMap<String, String>()
        for (key in KEYS) {
            val hit = resolve(context, key) ?: continue
            raw[key] = hit.first
            sources[key] = hit.second
            Log.i(TAG, "read: $key=${hit.first} source=${hit.second}")
        }

        if (!raw.containsKey("gesture_preference_screen")) {
            val legacy = raw["eraser_recognition_type"]
            val value = when (legacy) {
                null -> HOST_EMPTY_SCREEN_GESTURE
                "1", "3" -> "2"
                else -> "0"
            }
            raw["gesture_preference_screen"] = value
            sources["gesture_preference_screen"] =
                if (legacy == null) "host-empty:gesture_preference_screen" else "legacy:eraser_recognition_type"
        }
        if (!raw.containsKey("gesture_preference_slidebar")) {
            raw["gesture_preference_slidebar"] = HOST_EMPTY_SLIDEBAR_GESTURE
            sources["gesture_preference_slidebar"] = "host-empty:gesture_preference_slidebar"
        }

        val electronicBtn = raw["electronic_button"] ?: "0"
        val lamyBtn = raw["lamy_button"] ?: "0"
        val penButton = if (electronicBtn == "3" || lamyBtn == "1") GestureTool.LASSO else GestureTool.ERASER
        val writingPosture = raw["writing_posture"]?.toIntOrNull()
        val leftHand = if (writingPosture != null) writingPosture < 4 else raw["hand_dominance"] == "1"
        val sliderSide = if (writingPosture != null) (if (writingPosture < 4) 2 else 1) else (if (leftHand) 2 else 1)

        val resolved = Resolved(
            screenGesture = mapTool(raw["gesture_preference_screen"]),
            slidebarGesture = mapTool(raw["gesture_preference_slidebar"]),
            penButton = penButton,
            leftHand = leftHand,
            sliderSide = sliderSide,
            calibX = raw["diffX"]?.toIntOrNull() ?: 0,
            calibY = raw["diffY"]?.toIntOrNull() ?: 0,
            raw = raw,
            sources = sources,
        )
        lastResolved = resolved
        Log.i(TAG, "resolved: ${resolved.describe()}")
        return resolved
    }

    private val CALIBRATION_HINT = Regex(
        "calib|shift|diff|offset|posture|hand|stylus|pen_|emr|tilt|pressure",
        RegexOption.IGNORE_CASE,
    )

    
    fun dumpCalibrationHints(context: Context) {
        val resolver = context.contentResolver
        var printed = 0
        for (table in PROVIDER_TABLES) {
            try {
                val uri = Uri.parse("content://com.ratta.supernote.settings.provider/$table")
                resolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex("name")
                    val valueIdx = cursor.getColumnIndex("value")
                    if (nameIdx < 0) return@use
                    while (cursor.moveToNext() && printed < 80) {
                        val name = cursor.getString(nameIdx) ?: continue
                        if (!CALIBRATION_HINT.containsMatchIn(name)) continue
                        val value = if (valueIdx >= 0 && !cursor.isNull(valueIdx)) cursor.getString(valueIdx) else null
                        Log.i(TAG, "[Calib] provider:$table $name=$value")
                        printed++
                    }
                }
            } catch (e: Throwable) {
                Log.v(TAG, "dump: provider:$table ${e.javaClass.simpleName}")
            }
        }
        Log.i(TAG, "[Calib] dump done rows=$printed")
    }

    
    private fun mapTool(value: String?): GestureTool = when (value) {
        "0" -> GestureTool.ERASER
        "1" -> GestureTool.LASSO
        else -> GestureTool.OFF
    }

    private fun resolve(context: Context, key: String): Pair<String, String>? {
        val resolver = context.contentResolver
        for (table in PROVIDER_TABLES) {
            try {
                val uri = Uri.parse("content://com.ratta.supernote.settings.provider/$table")
                resolver.query(uri, arrayOf("value"), "name = ?", arrayOf(key), null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex("value")
                        if (index >= 0 && !cursor.isNull(index)) {
                            return cursor.getString(index) to "provider:$table"
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.v(TAG, "resolve: provider:$table/$key ${e.javaClass.simpleName}")
            }
        }
        readAndroid("Settings.System") { Settings.System.getString(resolver, key) }?.let { return it }
        readAndroid("Settings.Secure") { Settings.Secure.getString(resolver, key) }?.let { return it }
        readAndroid("Settings.Global") { Settings.Global.getString(resolver, key) }?.let { return it }
        return null
    }

    private inline fun readAndroid(source: String, reader: () -> String?): Pair<String, String>? = try {
        reader()?.let { it to source }
    } catch (e: Throwable) {
        Log.v(TAG, "resolve: $source ${e.javaClass.simpleName}")
        null
    }
}
