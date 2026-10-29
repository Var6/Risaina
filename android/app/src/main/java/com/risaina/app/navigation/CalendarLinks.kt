package com.risaina.app.navigation

import com.risaina.app.core.data.HH_MM
import com.risaina.app.core.model.CalendarKind
import com.risaina.app.feature.calendar.CalendarPrefill

/** The entry editor's route for a prefill from the chat or a note: plain strings, as the back stack keeps them. */
fun CalendarPrefill.toRoute() = CalendarEntryEdit(
    title = title, date = date?.toString(), time = time?.format(HH_MM),
    kind = kind.takeIf { it != CalendarKind.UNKNOWN }?.wire,
    sourceKind = sourceKind?.wire, sourceMessageId = sourceMessageId, sourceListId = sourceListId, sourceItemId = sourceItemId,
)
