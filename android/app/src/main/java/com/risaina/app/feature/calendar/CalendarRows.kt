package com.risaina.app.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.designsystem.theme.RisainaTextStyles
import com.risaina.app.core.domain.CalendarRules
import com.risaina.app.core.domain.CalendarText
import com.risaina.app.core.model.CalendarKind
import com.risaina.app.core.model.Partner
import com.risaina.app.feature.common.HeartIcon
import com.risaina.app.feature.common.NotificationsIcon
import java.time.LocalDate
import java.time.ZoneId

/** Who is looking: rows say "Added by Bea" and show Bea's clock when it differs. */
data class RowContext(val today: LocalDate, val zone: ZoneId, val myId: String?, val partner: Partner?, val partnerZone: ZoneId?)

/**
 * One line of the calendar: a to-do's checkbox or the entry's emoji (a heart for occasions), the
 * title, when it is on this phone (and the partner's clock), who added it, the countdown and a bell
 * when it reminds this phone.
 */
@Composable
fun AgendaRow(item: AgendaItem, ctx: RowContext, onOpen: () -> Unit, onToggle: (Boolean) -> Unit = {}, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onOpen).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (item) {
            is AgendaItem.Occasion -> Leading { Icon(HeartIcon, contentDescription = null, tint = Risaina.colors.accentWarm, modifier = Modifier.size(18.dp)) }
            is AgendaItem.Entry -> if (item.entry.kind == CalendarKind.TODO) {
                Checkbox(
                    checked = item.entry.done, onCheckedChange = onToggle,
                    modifier = Modifier.semantics { contentDescription = if (item.entry.done) "Done: ${item.entry.title}" else "Mark ${item.entry.title} done" },
                )
            } else {
                Leading {
                    val emoji = item.entry.emoji
                    if (emoji != null) Text(emoji, style = MaterialTheme.typography.titleMedium)
                    else Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            when (item) {
                is AgendaItem.Occasion -> {
                    Text(item.occasionDay.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(CalendarText.day(item.day, ctx.today.year), style = RisainaTextStyles.timestamp, color = Risaina.colors.textMuted)
                }
                is AgendaItem.Entry -> {
                    val e = item.entry
                    Text(
                        e.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textDecoration = if (e.done) TextDecoration.LineThrough else null,
                        color = if (e.done) Risaina.colors.textMuted else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(EntryRules.whenLine(item.occurrence, ctx.today.year), style = RisainaTextStyles.timestamp, color = Risaina.colors.textMuted)
                    val first = ctx.partner?.displayName?.substringBefore(" ")
                    CalendarRules.partnerStart(e, item.occurrence.date, ctx.zone, ctx.partnerZone)?.let { theirs ->
                        Text("${CalendarText.time(theirs.toLocalTime())} for ${first ?: "them"}", style = RisainaTextStyles.timestamp, color = Risaina.colors.textMuted)
                    }
                    if (ctx.partner != null && e.createdBy == ctx.partner.id) {
                        Text("Added by $first", style = RisainaTextStyles.timestamp, color = Risaina.colors.accentWarm)
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            val overdue = (item as? AgendaItem.Entry)?.overdue == true
            Text(
                CalendarText.countdown(item.day, ctx.today), style = MaterialTheme.typography.labelMedium,
                color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            val remindsMe = when (item) {
                is AgendaItem.Entry -> item.entry.reminders.isNotEmpty() && ctx.myId != null && ctx.myId in item.entry.remindUserIds && item.entry.kind != CalendarKind.UNKNOWN
                is AgendaItem.Occasion -> false
            }
            if (remindsMe) Icon(NotificationsIcon, contentDescription = "Reminds you", tint = Risaina.colors.textMuted, modifier = Modifier.padding(top = 2.dp).size(14.dp))
        }
    }
}

@Composable
private fun Leading(content: @Composable () -> Unit) {
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) { content() }
    }
}
