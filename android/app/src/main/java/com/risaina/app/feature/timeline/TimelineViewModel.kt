package com.risaina.app.feature.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.risaina.app.core.common.onFailure
import com.risaina.app.core.data.CoupleRepository
import com.risaina.app.core.data.MilestoneRepository
import com.risaina.app.core.domain.Countdown
import com.risaina.app.core.domain.CountdownCalculator
import com.risaina.app.core.model.Couple
import com.risaina.app.core.model.Milestone
import com.risaina.app.core.model.MilestoneKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class Upcoming(val title: String, val date: LocalDate, val countdown: Countdown, val kind: MilestoneKind, val milestone: Milestone?)

data class TimelineUiState(
    val couple: Couple? = null,
    val together: String? = null,
    val upcoming: List<Upcoming> = emptyList(),
    val milestones: List<Milestone> = emptyList(),
)

@HiltViewModel
class TimelineViewModel @Inject constructor(
    private val coupleRepository: CoupleRepository,
    sessionStore: com.risaina.app.core.datastore.SessionStore,
    private val milestoneRepository: MilestoneRepository,
) : ViewModel() {
    private val effects = Channel<String>(Channel.BUFFERED)
    val snackbars: Flow<String> = effects.receiveAsFlow()

    val state: StateFlow<TimelineUiState> = combine(coupleRepository.couple, milestoneRepository.all, coupleRepository.partner, sessionStore.profile) { couple, milestones, partner, me ->
        val today = LocalDate.now()
        val upcoming = buildList {
            couple?.nextMeetingDate?.takeIf { !it.isBefore(today) }?.let { add(Upcoming(couple.nextMeetingLabel ?: "Next time together", it, CountdownCalculator.until(it, today), MilestoneKind.MEETING, null)) }
            couple?.relationshipStartDate?.let { val n = CountdownCalculator.nextAnniversary(it, today); add(Upcoming("Anniversary", n, CountdownCalculator.until(n, today), MilestoneKind.ANNIVERSARY, null)) }
            com.risaina.app.core.domain.Birthday.parse(partner?.birthday)?.let { b ->
                val n = com.risaina.app.core.domain.Occasions.nextBirthday(b, today)
                add(Upcoming("${partner?.displayName}'s birthday" + (n.turning?.let { " · turning $it" } ?: ""), n.date, CountdownCalculator.until(n.date, today), MilestoneKind.CUSTOM, null))
            }
            com.risaina.app.core.domain.Birthday.parse(me?.birthday)?.let { b ->
                val n = com.risaina.app.core.domain.Occasions.nextBirthday(b, today)
                add(Upcoming("Your birthday", n.date, CountdownCalculator.until(n.date, today), MilestoneKind.CUSTOM, null))
            }
            milestones.forEach { m ->
                val d = if (m.kind == MilestoneKind.ANNIVERSARY) CountdownCalculator.nextAnniversary(m.date, today) else m.date
                if (!d.isBefore(today)) add(Upcoming(m.title, d, CountdownCalculator.until(d, today), m.kind, m))
            }
        }.sortedBy { it.date }
        TimelineUiState(
            couple = couple,
            together = couple?.relationshipStartDate?.let { CountdownCalculator.formatTogether(CountdownCalculator.together(it, today)) },
            upcoming = upcoming, milestones = milestones,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimelineUiState())

    init { viewModelScope.launch { milestoneRepository.refresh() } }

    fun setStartDate(d: LocalDate?) { viewModelScope.launch { coupleRepository.updateCouple(d, state.value.couple?.nextMeetingDate, state.value.couple?.nextMeetingLabel).onFailure { effects.trySend(it.message ?: "Couldn't save") } } }
    fun setNextMeeting(d: LocalDate?, label: String?) { viewModelScope.launch { coupleRepository.updateCouple(state.value.couple?.relationshipStartDate, d, label).onFailure { effects.trySend(it.message ?: "Couldn't save") } } }
    fun addMilestone(title: String, date: LocalDate, kind: MilestoneKind) { viewModelScope.launch { milestoneRepository.create(title, date, kind).onFailure { effects.trySend(it.message ?: "Couldn't save") } } }
    fun updateMilestone(id: String, title: String, date: LocalDate, kind: MilestoneKind) { viewModelScope.launch { milestoneRepository.update(id, title, date, kind).onFailure { effects.trySend(it.message ?: "Couldn't save") } } }
    fun deleteMilestone(id: String) { viewModelScope.launch { milestoneRepository.delete(id).onFailure { effects.trySend(it.message ?: "Couldn't delete") } } }
}
