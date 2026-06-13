package com.github.borgand.marginalia.ui.toolwindow

/** Short "2m ago" style age for a past epoch-millis timestamp. */
fun relativeTime(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val seconds = ((nowMs - timestampMs) / 1000).coerceAtLeast(0)
    return when {
        seconds < 5 -> "just now"
        seconds < 60 -> "${seconds}s ago"
        seconds < 3600 -> "${seconds / 60}m ago"
        seconds < 86_400 -> "${seconds / 3600}h ago"
        else -> "${seconds / 86_400}d ago"
    }
}
