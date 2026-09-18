package xyz.heylana.app.voice

/**
 * Heylana's lines, in order, one at a time. A new line waits behind the one being said;
 * only [stop] — something the user did — drops what is queued and ends the line in the
 * air, by moving the [generation] on: a line from an older generation is never played,
 * and a writer still finishing one sees it is over.
 */
class VoiceQueue<T> {

    private val items = ArrayDeque<Pair<Int, T>>()

    @Volatile
    var generation: Int = 0
        private set

    /** Queues [item]; the queue's length after it. */
    @Synchronized
    fun add(item: T): Int {
        items.addLast(generation to item)
        return items.size
    }

    /** The next line still worth saying, with its generation; null when there is none. */
    @Synchronized
    fun next(): Pair<Int, T>? {
        while (true) {
            val head = items.removeFirstOrNull() ?: return null
            if (head.first == generation) return head
        }
    }

    /** Lines of the current generation still waiting. */
    @Synchronized
    fun pending(): Int = items.count { it.first == generation }

    fun isCurrent(gen: Int): Boolean = gen == generation

    /** Ends the line in the air and drops the rest; how many were dropped. */
    @Synchronized
    fun stop(): Int {
        generation++
        val dropped = items.size
        items.clear()
        return dropped
    }
}
