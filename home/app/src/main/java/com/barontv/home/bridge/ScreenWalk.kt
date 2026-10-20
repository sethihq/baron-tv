package com.barontv.home.bridge

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reads what a music app shows, for /now: only when asked, never in the background. It walks the active
 * window's accessibility tree (the app's, checked by package) in screen order, keeps the nodes that show
 * something, and lets NowScreen pick the song out of them.
 *
 * The service's node cache only stays current while apps send it their changes, which an app started
 * before the service first read anything may not do; so the few nodes the song came from are read once
 * more from the app itself before answering. Every node obtained is recycled.
 */
internal class ScreenReader(private val root: () -> AccessibilityNodeInfo?) {
    private var lastApp = ""
    private var lastAt = Long.MIN_VALUE / 2
    private var last = ScreenTrack.NONE

    /** The song on [app]'s screen; asked again within a moment (the phone polling), the same answer. */
    @Synchronized
    fun read(app: String): ScreenTrack {
        val now = SystemClock.uptimeMillis()
        if (app == lastApp && now - lastAt < FRESH_MS) return last
        last = try {
            walk(app)
        } catch (e: RuntimeException) {
            // The app or the system went away mid-walk: nothing to say.
            ScreenTrack.NONE
        }
        lastApp = app
        lastAt = SystemClock.uptimeMillis()
        return last
    }

    /** The package of the window in front, for when no window event has said yet (the service just started). */
    fun activePackage(): String {
        val top = try {
            root()
        } catch (e: RuntimeException) {
            null
        } ?: return ""
        val pkg = top.packageName?.toString().orEmpty()
        top.release()
        return pkg
    }

    private fun walk(app: String): ScreenTrack {
        val top = root() ?: return ScreenTrack.NONE
        if (top.packageName?.toString() != app) {
            top.release()
            return ScreenTrack.NONE
        }
        val rect = Rect()
        top.getBoundsInScreen(rect)
        val screenHeight = rect.height()
        val kept = ScreenWalk.collect(top)
        try {
            val texts = kept.map { it.text }
            val pick = NowScreen.pick(texts, screenHeight)
            if (pick.used.isEmpty()) return pick.track
            val fresh = texts.toMutableList()
            for (i in pick.used.distinct()) {
                val node = kept[i].node
                fresh[i] = if (node.refresh()) ScreenWalk.text(node, rect) else texts[i].copy(text = "", desc = "")
            }
            return NowScreen.read(fresh, screenHeight)
        } finally {
            for (k in kept) k.node.release()
        }
    }

    private companion object {
        /** Answers within this long of a walk reuse it. */
        const val FRESH_MS = 800L
    }
}

internal object ScreenWalk {
    /** A node that shows text or a description, and the node itself (the caller recycles it). */
    class Kept(val text: ScreenText, val node: AccessibilityNodeInfo)

    // A music app's screen is a few hundred nodes at most; these keep a strange one from costing much.
    private const val MAX_NODES = 1000
    private const val MAX_DEPTH = 60
    private const val BUDGET_MS = 1500L

    /**
     * Walks [root]'s tree depth first in screen order, skipping what isn't visible. Takes [root]: every
     * node it obtains is recycled here except the ones returned, which are the caller's to recycle.
     */
    fun collect(root: AccessibilityNodeInfo): List<Kept> {
        val kept = ArrayList<Kept>()
        val stack = ArrayList<AccessibilityNodeInfo>()
        val depths = ArrayList<Int>()
        stack += root
        depths += 0
        var seen = 0
        val deadline = SystemClock.uptimeMillis() + BUDGET_MS
        val rect = Rect()
        try {
            while (stack.isNotEmpty()) {
                val node = stack.removeAt(stack.size - 1)
                val depth = depths.removeAt(depths.size - 1)
                var keep = false
                try {
                    seen++
                    if (!node.isVisibleToUser) continue
                    val t = text(node, rect)
                    if (t.text.isNotBlank() || t.desc.isNotBlank()) {
                        kept += Kept(t, node)
                        keep = true
                    }
                    // Past the limits the walk stops going deeper and just finishes what it holds.
                    if (depth < MAX_DEPTH && seen + stack.size < MAX_NODES && SystemClock.uptimeMillis() < deadline) {
                        for (i in node.childCount - 1 downTo 0) {
                            val child = node.getChild(i) ?: continue
                            stack += child
                            depths += depth + 1
                        }
                    }
                } finally {
                    if (!keep) node.release()
                }
            }
        } catch (e: RuntimeException) {
            for (n in stack) n.release()
            for (k in kept) k.node.release()
            throw e
        }
        return kept
    }

    /** What [node] shows, and where. */
    fun text(node: AccessibilityNodeInfo, rect: Rect): ScreenText {
        node.getBoundsInScreen(rect)
        val cls = node.className?.toString().orEmpty()
        return ScreenText(
            left = rect.left,
            top = rect.top,
            right = rect.right,
            bottom = rect.bottom,
            text = node.text?.toString().orEmpty(),
            desc = node.contentDescription?.toString().orEmpty(),
            id = node.viewIdResourceName.orEmpty(),
            cls = cls,
            seek = node.rangeInfo != null || cls.endsWith("SeekBar") || cls.endsWith("ProgressBar"),
        )
    }
}

/**
 * Gives a node back to the framework's pool. Android 13 made recycling a no-op (and deprecated it), but
 * this TV runs Android 9, where every node obtained and not recycled is a small leak.
 */
@Suppress("DEPRECATION")
private fun AccessibilityNodeInfo.release() = recycle()
