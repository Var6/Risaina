package com.risaina.app.feature.home

/** A shortcut tile on Home. Widgets and Their widget live in Settings → Widgets. */
enum class HomeTile(val label: String) {
    MESSAGES("Messages"),
    LISTS("Lists"),
    MEMORIES("Memories"),
    CALENDAR("Calendar"),
    TIMELINE("Timeline"),
    GIFTS("Gifts"),
    WAKE("Wake"),
}

/** Which tiles Home shows, and how they fill its even grid. Pure (HomeTilesTest). */
object HomeTiles {
    /** Tiles in a row: the six tiles make two even rows of three. */
    const val PER_ROW = 3

    /**
     * In order. The Timeline lives on as "Our story" inside the calendar; an older server has no
     * calendar yet, so it gets the Timeline tile in the calendar's place.
     */
    fun of(calendar: Boolean): List<HomeTile> = listOf(
        HomeTile.MESSAGES, HomeTile.LISTS, HomeTile.MEMORIES,
        if (calendar) HomeTile.CALENDAR else HomeTile.TIMELINE,
        HomeTile.GIFTS, HomeTile.WAKE,
    )

    /** [of] split into rows of [PER_ROW]; only the last row can be shorter. */
    fun rows(calendar: Boolean): List<List<HomeTile>> = of(calendar).chunked(PER_ROW)
}
