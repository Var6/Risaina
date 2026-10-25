package com.risaina.app.feature.ping

/**
 * Expression catalogue: emoji grouped by feeling, plus "gift stickers". A gift travels as a
 * custom ping whose message starts with [GIFT_PREFIX], so it needs no new server type and old
 * app versions still show something sensible ("🎁 A rose for you").
 */
object Expressions {
    const val GIFT_PREFIX = "🎁 "

    data class EmojiGroup(val name: String, val emoji: List<String>)

    val groups = listOf(
        EmojiGroup("Love", listOf("❤️", "🩷", "💕", "💞", "💗", "💖", "💘", "💝", "😘", "🥰", "😍", "💋", "🫶", "💌", "♾️", "💍")),
        EmojiGroup("Missing you", listOf("🥺", "🌙", "✨", "💭", "🌧️", "🫂", "🛫", "📍", "🕰️", "🌍", "📞", "🌌")),
        EmojiGroup("Comfort", listOf("🤗", "🫂", "☕", "🧸", "🌸", "🍵", "🛌", "🌿", "🕯️", "💐", "🌻", "🌈")),
        EmojiGroup("Playful", listOf("😜", "🙈", "😏", "🤭", "🥳", "🎉", "😂", "👀", "🤪", "🐣", "🦋", "🍑")),
        EmojiGroup("Day & night", listOf("☀️", "🌅", "🌤️", "🌙", "🌃", "⭐", "😴", "🥱", "🛏️", "🌠", "🌄", "🌛")),
        EmojiGroup("Proud of you", listOf("👏", "🏆", "💪", "🎓", "🌟", "🙌", "🔥", "💯", "🥇", "🚀", "📚", "✅")),
    )

    data class Gift(val emoji: String, val name: String, val line: String)

    val gifts = listOf(
        Gift("🌹", "Rose", "A rose for you"),
        Gift("💐", "Bouquet", "Flowers, just because"),
        Gift("🧸", "Teddy", "A teddy to hold tonight"),
        Gift("☕", "Coffee date", "Virtual coffee? My treat"),
        Gift("🍫", "Chocolate", "Something sweet for you"),
        Gift("🎂", "Cake", "A slice of cake, with love"),
        Gift("🍕", "Pizza night", "Pizza night, together apart"),
        Gift("🎧", "Our song", "Play our song and think of me"),
        Gift("🌙", "The moon", "Same moon, two windows"),
        Gift("⭐", "A star", "Named a star after you"),
        Gift("🫂", "Hug coupon", "One long hug, redeemable when we meet"),
        Gift("💌", "Love letter", "A letter's worth of love"),
        Gift("🎟️", "Date ticket", "Good for one date, anywhere"),
        Gift("🍦", "Ice cream", "Ice cream, two spoons"),
        Gift("🕯️", "Candlelight", "A candlelit evening, soon"),
        Gift("🌻", "Sunflower", "Sunshine for your day"),
    )

    fun giftMessage(g: Gift) = "$GIFT_PREFIX${g.line}"
    fun isGift(message: String?) = message?.startsWith(GIFT_PREFIX) == true
    fun giftLine(message: String?) = message?.removePrefix(GIFT_PREFIX)
}
