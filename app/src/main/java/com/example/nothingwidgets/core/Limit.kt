package com.aurikqq.noswidgets.core

fun limitExceeded(screenMillis: Long?, limitMinutes: Int): Boolean {
    require(limitMinutes > 0)
    return screenMillis != null && screenMillis > limitMinutes * 60_000L
}
