package com.risaina.app.core.notifications

/**
 * What a game notification says, from the partner's `game.updated` action. Pure, so every
 * (kind, action) pair is unit-tested. The notifier prefixes the partner's first name
 * ("Bea challenged you to…"); null means post nothing.
 */
object GameNotificationText {
    private const val RPS = "rps"
    private const val TICTACTOE = "tictactoe"
    private const val TRIVIA = "trivia"
    private const val THIS_OR_THAT = "this_or_that"

    /** Not worth a notification: one trivia answer, a cancelled game, a draw called off. */
    val SILENT = setOf("answered", "cancelled", "ended")

    /** These close the game: an earlier "your turn" notification is now stale and is removed. */
    val CLEARS = setOf("cancelled", "ended")

    /** Actions that ask the viewer to do something; stale once the game has closed since. */
    private val ASKS_FOR_A_MOVE = setOf("created", "moved", "draw", "rematch", "finished", "challenged", "chosen", "done", "rejected")

    /** Tic-tac-toe moves come in quick succession: update the notification without ringing again. */
    fun alertOnce(action: String): Boolean = action == "moved"

    /**
     * True when the game has closed since this event (cancelled, or completed without the viewer),
     * so a "your turn" text would be wrong. [currentStatus] is the newest copy the app has; a closed
     * game never reopens, so an older copy can't wrongly hide a notification.
     */
    fun isStale(action: String, currentStatus: String?): Boolean = when (currentStatus) {
        "cancelled" -> action != "cancelled"
        "completed" -> action in ASKS_FOR_A_MOVE
        else -> false
    }

    /** [topic] is the trivia category name, when the event carries one. */
    fun body(kind: String, action: String, topic: String? = null): String? {
        if (action in SILENT) return null
        return when (action) {
            "created" -> when (kind) {
                RPS -> "challenged you to Stone Paper Scissors Thread ✊✋✌️🧵"
                TICTACTOE -> "challenged you to tic-tac-toe ❌⭕ You go first"
                TRIVIA -> "started a trivia duel: 5 questions" + (topic?.takeIf { it.isNotBlank() }?.let { " on $it" } ?: "") + " 🧠"
                THIS_OR_THAT -> "started This or That 💞 7 quick picks, answered blind"
                else -> "started a new game. Update Risaina to play 🎲"
            }
            "moved" -> when (kind) {
                TICTACTOE -> "played. Your turn ❌⭕"
                else -> "made their move. Your turn!"
            }
            "finished" -> when (kind) {
                TRIVIA -> "finished their 5 questions. Your turn 🧠"
                THIS_OR_THAT -> "made all 7 picks. Your turn 💞"
                else -> null
            }
            "revealed" -> when (kind) {
                TICTACTOE -> "got three in a row 🏆 Open the chat to see"
                TRIVIA -> "finished too. The scores are in 🧠"
                else -> "played. The result is in, open the chat to see"
            }
            "draw" -> when (kind) {
                TICTACTOE -> "filled the last square. It's a draw 🤝 Rematch?"
                TRIVIA -> "finished too. It's a tie 🤝 Rematch?"
                else -> "played. It's a draw 🤝 Rematch?"
            }
            "matched" -> if (kind == THIS_OR_THAT) "finished too. See how in sync you are 💞" else null
            "rematch" -> if (kind == TRIVIA) "wants a rematch: 5 new questions 🧠" else "wants a rematch"
            "challenged" -> "sealed a truth and a dare for you 😈 Pick one"
            "chosen" -> "made their pick. Now it's on them"
            "done" -> "says it's done. Confirm it?"
            "confirmed" -> "confirmed it ✓ You're off the hook"
            "rejected" -> "says not quite. Try again 😄"
            else -> null
        }
    }
}
