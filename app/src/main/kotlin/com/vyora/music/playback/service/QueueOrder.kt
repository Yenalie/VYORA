package com.vyora.music.playback.service

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random

@UnstableApi
class PlayNextShuffleOrder(private val shuffled: IntArray, private val random: Random = Random.Default) : ShuffleOrder {
    private val position = IntArray(shuffled.size).also { pos -> shuffled.forEachIndexed { i, v -> pos[v] = i } }

    override fun getLength() = shuffled.size
    override fun getNextIndex(index: Int): Int = position[index].let { if (it + 1 < shuffled.size) shuffled[it + 1] else -1 }
    override fun getPreviousIndex(index: Int): Int = position[index].let { if (it > 0) shuffled[it - 1] else -1 }
    override fun getLastIndex() = shuffled.lastOrNull() ?: -1
    override fun getFirstIndex() = shuffled.firstOrNull() ?: -1

    fun order(): List<Int> = shuffled.toList()

    override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder {
        if (shuffled.isEmpty()) return PlayNextShuffleOrder(randomPermutation(insertionCount, random), random)
        val shifted = shuffled.map { if (it >= insertionIndex) it + insertionCount else it }.toMutableList()
        val inserted = (insertionIndex until insertionIndex + insertionCount).toList()
        if (insertionIndex >= shuffled.size) {
            shifted.addAll(inserted)
        } else {
            val anchor = insertionIndex - 1
            val at = if (anchor < 0) 0 else shifted.indexOf(anchor) + 1
            shifted.addAll(at, inserted)
        }
        return PlayNextShuffleOrder(shifted.toIntArray(), random)
    }

    override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder {
        val count = indexToExclusive - indexFrom
        val kept = shuffled.filter { it < indexFrom || it >= indexToExclusive }
            .map { if (it >= indexToExclusive) it - count else it }
        return PlayNextShuffleOrder(kept.toIntArray(), random)
    }

    override fun cloneAndClear(): ShuffleOrder = PlayNextShuffleOrder(IntArray(0), random)

    companion object {
        fun randomPermutation(n: Int, random: Random): IntArray = (0 until n).shuffled(random).toIntArray()

        fun startingWith(first: Int, length: Int, random: Random = Random.Default): PlayNextShuffleOrder {
            val rest = (0 until length).filter { it != first }.shuffled(random)
            return PlayNextShuffleOrder((listOf(first) + rest).toIntArray(), random)
        }
    }
}
