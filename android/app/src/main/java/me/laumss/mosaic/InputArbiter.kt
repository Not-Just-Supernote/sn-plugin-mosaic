package me.laumss.mosaic

import android.os.SystemClock


object InputArbiter {
    
    const val PEN_PRIORITY_TAIL_MS = 300L
    
    const val SLIDER_BLOCK_MS = 600L

    @Volatile private var penContact = false
    @Volatile private var penHover = false
    
    @Volatile private var penLeftAt = 0L
    @Volatile private var sliderBlockUntil = 0L

    fun onPenContact(contact: Boolean) {
        if (penContact == contact) return
        penContact = contact
        if (!contact) penLeftAt = SystemClock.uptimeMillis()
    }

    fun onPenHover(hover: Boolean) {
        if (penHover == hover) return
        penHover = hover
        if (!hover) penLeftAt = SystemClock.uptimeMillis()
    }

    fun onSliderActivity() {
        sliderBlockUntil = SystemClock.uptimeMillis() + SLIDER_BLOCK_MS
    }

    fun isPenContact(): Boolean = penContact

    fun penPriority(now: Long = SystemClock.uptimeMillis()): Boolean =
        penContact || penHover || (penLeftAt != 0L && now - penLeftAt < PEN_PRIORITY_TAIL_MS)

    fun touchBlockedBySlider(now: Long = SystemClock.uptimeMillis()): Boolean = now < sliderBlockUntil

    fun reset() {
        penContact = false
        penHover = false
        penLeftAt = 0L
        sliderBlockUntil = 0L
    }

    fun describe(): String =
        "penContact=$penContact hover=$penHover priority=${penPriority()} sliderBlock=${touchBlockedBySlider()}"
}
