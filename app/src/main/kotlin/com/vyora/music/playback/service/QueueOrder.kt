package com.vyora.music.playback.service

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random

/**
 * Shuffle order that behaves like Apple Music's:
 *  - songs added with "Play Next" (inserted right after the current one) play next, also
 *    while shuffle is on. ExoPlayer's default order drops them at a random spot;
 *  - "Play Last" (appended at the end) plays after everything else;
 *  - a freshly built order can start with a given song, so turning shuffle on keeps the
 *    current song and randomises only what comes after it.
 *
 * [shuffled] lists playlist indices in play order.
 */
@UnstableApi
class PlayNextShuffleOrder(private val shuffled: IntArray, private val random: Random = Random.Default) : ShuffleOrder {
    private val position = IntArray(shuffled.size).also { pos -> shuffled.forEachIndexed { i, v -> pos[v] = i } }

    override fun getLength() = shuffled.size
    override fun getNextIndex(index: Int): Int = position[index].let { if (it + 1 < shuffled.size) shuffled[it + 1] else -1 }
    override fun getPreviousIndex(index: Int): Int = position[index].let { if (it > 0) shuffled[it - 1] else -1 }
    override fun getLastIndex() = shuffled.lastOrNull() ?: -1
    override fun getFirstIndex() = shuffled.firstOrNull() ?: -1

    /** Play order as a list, for tests and the "Up Next" list. */
    fun order(): List<Int> = shuffled.toList()

    override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder {
        // Initial fill (or a whole new list): plain random order.
        if (shuffled.isEmpty()) return PlayNextShuffleOrder(randomPermutation(insertionCount, random), random)
        // Existing indices at or after the insertion point move up.
        val shifted = shuffled.map { if (it >= insertionIndex) it + insertionCount else it }.toMutableList()
        val inserted = (insertionIndex until insertionIndex + insertionCount).toList()
        if (insertionIndex >= shuffled.size) {
            shifted.addAll(inserted) // "Play Last": after everything.
        } else {
            // "Play Next": right after the song that precedes the insertion point
            // (the current song, when inserting at current + 1).
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

        /** [first] plays first, the rest in random order. */
        fun startingWith(first: Int, length: Int, random: Random = Random.Default): PlayNextShuffleOrder {
            val rest = (0 until length).filter { it != first }.shuffled(random)
            return PlayNextShuffleOrder((listOf(first) + rest).toIntArray(), random)
        }
    }
}
