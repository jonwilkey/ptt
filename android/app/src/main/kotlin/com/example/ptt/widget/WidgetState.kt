package com.example.ptt.widget

import com.example.ptt.data.ContactEntity
import com.example.ptt.data.ContactRepository
import com.example.ptt.data.RosterEntity
import com.example.ptt.data.RosterRepository
import com.example.ptt.data.SettingsRepository
import com.example.ptt.net.DeviceTokenStore
import com.example.ptt.talk.FamilyPerson
import com.example.ptt.talk.familyPeople
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

/** How reachable a person was at the last contacts refresh (`GET /v1/contacts`). */
enum class WidgetPresence {
    OK,
    WARNING,
    OFFLINE;

    companion object {
        /** `ok` | `warning` | `offline`; anything else, including no contact row yet, is offline. */
        fun of(wire: String?): WidgetPresence = when (wire) {
            "ok" -> OK
            "warning" -> WARNING
            else -> OFFLINE
        }
    }
}

data class WidgetPerson(val person: FamilyPerson, val presence: WidgetPresence)

/** Everything the widget draws. Incoming pages are never part of it: they arrive as notifications. */
sealed interface WidgetState {
    /** Not enrolled, or no readable roster yet: one line that opens the app. */
    data object NeedsSetup : WidgetState

    /** [checklistWarning]: the setup checklist has a red row, so pages may not reach this phone. */
    data class Family(val people: List<WidgetPerson>, val checklistWarning: Boolean) : WidgetState
}

/** The pure mapping from what is stored to what is drawn. */
internal fun widgetState(
    enrolled: Boolean,
    roster: RosterEntity?,
    deviceId: String?,
    contacts: List<ContactEntity>,
    checklistRegressed: Boolean
): WidgetState {
    val people = if (enrolled) familyPeople(roster, deviceId) else null
    if (people == null) return WidgetState.NeedsSetup
    val presence = contacts.associate { it.userId to it.presence }
    return WidgetState.Family(
        people.map { WidgetPerson(it, WidgetPresence.of(presence[it.userId])) },
        checklistRegressed
    )
}

/** The widget's state from the stores, as a flow (for the updater) and once (for a render). */
class WidgetStates(
    private val rosters: RosterRepository,
    private val contacts: ContactRepository,
    private val settings: SettingsRepository,
    private val tokens: DeviceTokenStore
) {
    val states: Flow<WidgetState> = combine(
        settings.enrolled,
        rosters.roster,
        contacts.contacts,
        settings.checklistRegressed
    ) { enrolled, roster, list, regressed ->
        widgetState(enrolled, roster, tokens.deviceId(), list, regressed)
    }.distinctUntilChanged()

    suspend fun current(): WidgetState = states.first()
}
