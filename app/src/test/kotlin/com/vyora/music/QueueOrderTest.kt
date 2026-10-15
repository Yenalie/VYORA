package com.vyora.music

import com.vyora.music.playback.service.PlayNextShuffleOrder
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueOrderTest {
    private fun order(vararg ix: Int) = PlayNextShuffleOrder(ix, Random(1))

    @Test fun playNextGoesRightAfterCurrentWhileShuffled() {
        val next = order(2, 0, 3, 1).cloneAndInsert(3, 1) as PlayNextShuffleOrder
        assertEquals(listOf(2, 3, 0, 4, 1), next.order())
    }

    @Test fun playLastGoesToTheEnd() {
        val next = order(2, 0, 1).cloneAndInsert(3, 2) as PlayNextShuffleOrder
        assertEquals(listOf(2, 0, 1, 3, 4), next.order())
    }

    @Test fun removeShiftsLaterIndices() {
        val next = order(3, 0, 2, 1).cloneAndRemove(1, 2) as PlayNextShuffleOrder
        assertEquals(listOf(2, 0, 1), next.order())
    }

    @Test fun startingWithKeepsCurrentFirstAndCoversEverything() {
        val o = PlayNextShuffleOrder.startingWith(4, 10, Random(7))
        assertEquals(4, o.firstIndex)
        assertEquals((0 until 10).toSet(), o.order().toSet())
        assertEquals(10, o.length)
    }

    @Test fun navigationFollowsPlayOrder() {
        val o = order(2, 0, 1)
        assertEquals(0, o.getNextIndex(2))
        assertEquals(-1, o.getNextIndex(1))
        assertEquals(2, o.getPreviousIndex(0))
        assertEquals(1, o.lastIndex)
    }

    @Test fun initialFillIsAFullPermutation() {
        val o = PlayNextShuffleOrder(IntArray(0), Random(3)).cloneAndInsert(0, 6) as PlayNextShuffleOrder
        assertEquals((0 until 6).toSet(), o.order().toSet())
    }
}
