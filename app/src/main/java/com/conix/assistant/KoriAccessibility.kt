package com.conix.assistant.access

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Best-effort UI automation limited to the YouTube app (see accessibility_config.xml). */
class KoriAccessibility : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }

    fun clickMatching(match: (String) -> Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = find(root, match) ?: return false
        return clickNode(node)
    }

    private fun find(n: AccessibilityNodeInfo, match: (String) -> Boolean): AccessibilityNodeInfo? {
        val label = ((n.contentDescription?.toString() ?: "") + " " + (n.text?.toString() ?: "")).trim().lowercase()
        if (label.isNotEmpty() && match(label)) return n
        for (i in 0 until n.childCount) {
            val ch = n.getChild(i) ?: continue
            val r = find(ch, match)
            if (r != null) return r
        }
        return null
    }

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var c: AccessibilityNodeInfo? = n
        var depth = 0
        while (c != null && depth < 6) {
            if (c.isClickable) return c.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            c = c.parent
            depth++
        }
        return false
    }

    companion object {
        @Volatile var instance: KoriAccessibility? = null
    }
}
