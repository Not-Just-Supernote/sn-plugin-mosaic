package me.laumss.mosaic


class ToolArbiter {

    
    enum class Tool { ERASER, LASSO, SHAPE }
    enum class Source { PEN_BUTTON, SCREEN, SLIDEBAR, SHAPE }
    enum class Settle { ON_PEN_UP, ON_NEXT_PEN_DOWN }
    enum class Physical { STYLUS, RUBBER }

    data class State(val eraser: Boolean, val lasso: Boolean, val shape: Boolean = false) {
        companion object {
            val NONE = State(eraser = false, lasso = false)
            fun of(tool: Tool?): State = when (tool) {
                null -> NONE
                Tool.ERASER -> State(eraser = true, lasso = false)
                Tool.LASSO -> State(eraser = false, lasso = true)
                Tool.SHAPE -> State(eraser = false, lasso = false, shape = true)
            }
        }
    }

    class Transition(
        val before: State,
        val after: State,
        
        val keepSelection: Boolean,
        val reason: String,
    ) {
        val changed: Boolean get() = before != after
    }

    private class Override(
        val source: Source,
        var tool: Tool,
        var settle: Settle,
        var exitRequested: Boolean = false,
        
        val armed: Boolean,
        var armedConsumed: Boolean = false,
        
        var held: Boolean = true,
        val physical: Physical?,
    )

    private var base: Tool? = null
    private val overrides = ArrayList<Override>()
    private var penContact = false

    fun effectiveTool(): Tool? = overrides.lastOrNull()?.tool ?: base

    fun effective(): State = State.of(effectiveTool())

    fun baseTool(): Tool? = base

    fun hasOverride(source: Source): Boolean = overrides.any { it.source == source }

    
    fun penButtonPhysical(): Physical? =
        overrides.firstOrNull { it.source == Source.PEN_BUTTON }?.physical

    
    fun penButtonHeld(): Boolean = overrides.any { it.source == Source.PEN_BUTTON && it.held }

    
    fun toggleBase(tool: Tool): Transition = transition("toggle-base:$tool", false) {
        base = if (base == tool) null else tool
    }

    
    fun clearBase(): Transition = transition("clear-base", false) { base = null }

    
    fun pushOverride(
        source: Source,
        tool: Tool,
        settle: Settle,
        armed: Boolean = false,
        physical: Physical? = null,
    ): Transition? {
        if (hasOverride(source)) return null
        return transition("push:$source/$tool", false) {
            overrides.add(
                Override(
                    source = source,
                    tool = tool,
                    settle = settle,
                    armed = armed,
                    physical = physical,
                ),
            )
        }
    }

    
    fun releaseOverride(source: Source): Transition = transition("release:$source", true) {
        val item = overrides.firstOrNull { it.source == source } ?: return@transition
        item.held = false
        item.exitRequested = true
        val awaitingArmedStroke = item.armed && !item.armedConsumed
        if (!penContact && !awaitingArmedStroke) remove(source)
    }

    
    fun forceExit(source: Source): Transition = transition("force-exit:$source", true) { remove(source) }

    
    fun replaceOverride(source: Source, tool: Tool, settle: Settle): Transition? =
        transition("replace:$source/$tool", true) {
            val item = overrides.firstOrNull { it.source == source } ?: return@transition
            item.tool = tool
            item.settle = settle
            item.exitRequested = false
        }

    
    fun reset() {
        base = null
        overrides.clear()
        penContact = false
    }

    
    fun penDown(): Transition = transition("pen-down", true) {
        penContact = true
        for (item in ArrayList(overrides)) {
            if (item.armed && !item.armedConsumed) {
                item.armedConsumed = true
                continue
            }
            if (item.exitRequested && item.settle == Settle.ON_NEXT_PEN_DOWN && !item.held) {
                remove(item.source)
            }
        }
    }

    
    fun penUp(): Transition = transition("pen-up", true) {
        penContact = false
        for (item in ArrayList(overrides)) {
            if (!item.exitRequested) continue
            if (item.settle == Settle.ON_PEN_UP || (item.armed && item.armedConsumed)) {
                remove(item.source)
            }
        }
    }

    fun describe(): String {
        val stack = overrides.joinToString(",") {
            "${it.source}/${it.tool}${if (it.exitRequested) "(exit)" else ""}${if (it.armed) "(armed)" else ""}"
        }
        return "base=${base ?: "none"} overrides=[$stack] penContact=$penContact"
    }

    private fun remove(source: Source) {
        overrides.removeAll { it.source == source }
    }

    private inline fun transition(reason: String, keepSelection: Boolean, mutate: () -> Unit): Transition {
        val before = effective()
        mutate()
        val after = effective()
        return Transition(before, after, keepSelection, reason)
    }
}
