package com.example.ptt.setup

import com.example.ptt.crypto.Roster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RosterViewTest {
    private val admin = TestAdmin("11111111-1111-4111-8111-111111111111").device
    private val member = NewPhone().entry
    private val roster = Roster(2, ISSUED_AT, listOf(admin, member).sortedBy { it.id })

    @Test
    fun adminScreensAreForDevicesListedAsAdminsInTheRosterOnly() {
        assertTrue(RosterView(roster, admin.id).isAdmin)
        assertFalse(RosterView(roster, member.id).isAdmin)
        assertFalse("not in the roster", RosterView(roster, "99999999-9999-4999-8999-999999999999").isAdmin)
        assertFalse("not enrolled", RosterView(roster, null).isAdmin)
    }

    @Test
    fun peopleListsOneEntryPerPerson() {
        val second = NewPhone(userId = member.userId, displayName = member.displayName).entry
        val two = Roster(3, ISSUED_AT, listOf(admin, member, second).sortedBy { it.id })
        assertEquals(2, RosterView(two, admin.id).people.size)
    }
}
