package com.koyo.screenwarden

/**
 * Process-local visibility hint for proactive delivery
 *
 * A chat bubble is enough while the conversation is on screen. In every other
 * state the same delivery also needs a system notification or it is effectively
 * invisible until the user happens to reopen tiyo
 */
object ProactivePresence {
    @Volatile
    private var chatVisible = false

    fun setChatVisible(visible: Boolean) {
        chatVisible = visible
    }

    fun shouldNotify(): Boolean = !chatVisible
}
