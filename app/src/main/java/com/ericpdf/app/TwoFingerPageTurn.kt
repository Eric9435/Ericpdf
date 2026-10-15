package com.ericpdf.app

import kotlin.math.abs

/** Pure direction classifier; the Compose gesture listener owns pointer collection. */
internal object TwoFingerPageTurn {
    /** Return +1 for next, -1 for previous or 0 to keep the current page. */
    fun direction(dx:Float,dy:Float,wasPinched:Boolean,zoom:Float,thresholdPx:Float):Int {
        if(wasPinched || zoom>1.05f || thresholdPx<=0f) return 0
        if(abs(dx)<thresholdPx || abs(dx)<=abs(dy)*1.45f) return 0
        return if(dx<0) 1 else -1
    }
}
