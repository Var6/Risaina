package com.risaina.app.core.sync

/** Asks the home-screen widgets to re-render from the snapshot store. Implemented by the widgets feature. */
interface WidgetRefresher {
    suspend fun refreshAll()
}
