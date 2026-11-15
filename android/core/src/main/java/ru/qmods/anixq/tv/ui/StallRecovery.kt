package ru.qmods.anixq.tv.ui

/** Shared phone/TV recovery. Short buffers and deliberate source changes are not network failures. */
class StallRecovery {
    enum class Action { NONE, MIRROR, LOWER_QUALITY }
    private var since: Long? = null
    private var counted = false
    private var ignoreUntil = 0L
    private var lastDrop: Long? = null
    private val stalls = ArrayList<Long>()

    fun reset(now: Long, clearHistory: Boolean = false) {
        since = null
        counted = false
        ignoreUntil = now + 10_000
        if (clearHistory) stalls.clear()
    }

    fun update(now: Long, buffering: Boolean, automaticQuality: Boolean): Action {
        if (!buffering || now < ignoreUntil) {
            since = null; counted = false
            return Action.NONE
        }
        val started = since ?: now.also { since = it }
        val elapsed = now - started
        if (elapsed < 6_000) return Action.NONE
        stalls.removeAll { now - it > 180_000 }
        if (!counted) { stalls.add(now); counted = true }
        if (automaticQuality && stalls.size >= 3 && (lastDrop == null || now - lastDrop!! >= 180_000)) {
            lastDrop = now
            stalls.clear()
            reset(now)
            return Action.LOWER_QUALITY
        }
        if (elapsed >= 18_000) {
            reset(now)
            return Action.MIRROR
        }
        return Action.NONE
    }
}
