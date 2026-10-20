package com.barontv.remote.ui.tvtab

import androidx.compose.animation.core.snap
import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dock list's drag, without a screen: the slots rows move to, the rubber band and the drop. */
class DockDragTest {
    @Test
    fun rowsBetweenMakeRoomGoingDown() {
        // Second row dragged to the fourth place: the third and fourth move up one, the rest stay.
        assertEquals(listOf(0, 1, 1, 2, 4, 5), (0 until 6).map { slotOf(it, from = 1, to = 3) })
    }

    @Test
    fun rowsBetweenMakeRoomGoingUp() {
        // Fifth row dragged to the second place: the second to fourth move down one.
        assertEquals(listOf(0, 2, 3, 4, 4, 5), (0 until 6).map { slotOf(it, from = 4, to = 1) })
    }

    @Test
    fun nothingMovesUntilTheRowLeavesItsSlot() {
        assertEquals(listOf(0, 1, 2), (0 until 3).map { slotOf(it, from = 1, to = 1) })
    }

    @Test
    fun aSlotChangesHalfwayToTheNextRow() {
        val drag = DockDrag()
        drag.lift("youtube", 1)
        assertFalse("27 dp is still the row's own slot", drag.moveBy(27f, count = 6))
        assertEquals(1, drag.to)
        assertTrue("28 dp is half a row: the next slot", drag.moveBy(1f, count = 6))
        assertEquals(2, drag.to)
        // One and a half rows rounds up, as the prototype's Math.round does.
        drag.moveBy(56f, count = 6)
        assertEquals(84f, drag.offset, 0.001f)
        assertEquals(3, drag.to)
    }

    @Test
    fun pastTheEndsTheRowFollowsWithResistance() {
        val drag = DockDrag()
        drag.lift("prime", 0)
        drag.moveBy(-100f, count = 6)
        assertEquals(0, drag.to)
        assertTrue("it gives a little", drag.offset < 0f)
        assertTrue("but much less than the finger", drag.offset > -60f)
        assertEquals(-rubberBand(100f), drag.offset, 0.001f)
    }

    @Test
    fun theRubberBandStartsSoftAndNeverPassesItsLimit() {
        assertEquals(0f, rubberBand(0f), 0f)
        assertTrue(rubberBand(10f) < 10f)
        assertTrue(rubberBand(10_000f) < 140f)
    }

    @Test
    fun movesIgnoredWithNothingLifted() {
        val drag = DockDrag()
        assertFalse(drag.moveBy(200f, count = 6))
        assertEquals(0f, drag.offset, 0f)
    }

    @Test
    fun theDropSettlesIntoTheSlotAndClears() = runBlocking {
        val drag = DockDrag()
        drag.lift("youtube", 1)
        drag.moveBy(70f, count = 6)
        assertEquals(2, drag.to)
        withContext(EveryFrameAtOnce) { drag.settle(snap()) }
        assertEquals("exactly one row down", 56f, drag.offset, 0.001f)
        assertTrue(drag.settling)
        drag.clear()
        assertNull(drag.key)
        assertFalse(drag.active)
        assertEquals(0f, drag.offset, 0f)
    }
}

/** Animations need frames; this hands them out as fast as they are asked for. */
private object EveryFrameAtOnce : MonotonicFrameClock {
    private var now = 0L

    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
        now += 16_000_000L
        return onFrame(now)
    }
}
