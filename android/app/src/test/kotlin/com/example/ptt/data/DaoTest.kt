package com.example.ptt.data

import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DaoTest {
    private lateinit var db: PttDatabase

    @Before
    fun setUp() {
        db = TestDb.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private val clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC)

    @Test
    fun contactsAreReplacedAsAWholeAndSortedByName() = runBlocking {
        val repo = ContactRepository(db.contactDao(), clock)
        repo.replaceAll(
            listOf(
                com.example.ptt.net.ContactDto("u2", "bea", false, "warning"),
                com.example.ptt.net.ContactDto("u1", "Alex", true, "ok")
            )
        )
        assertEquals(listOf("Alex", "bea"), repo.contacts.first().map { it.displayName })
        assertEquals(1_000L, repo.contacts.first().first().updatedAt)
        repo.replaceAll(listOf(com.example.ptt.net.ContactDto("u3", "Cy", false, "offline")))
        assertEquals(listOf("u3"), repo.contacts.first().map { it.userId })
    }

    @Test
    fun rosterIsASingleRowStoredVerbatim() = runBlocking {
        val repo = RosterRepository(db.rosterDao(), clock)
        assertNull(repo.current())
        val signed = """{ "payload":"x",  "signer":"s","sig":"y" }"""
        repo.save(7, signed, """{"version":7}""")
        repo.save(8, signed, """{"version":8}""")
        val row = repo.current()!!
        assertEquals(8L, row.version)
        assertEquals(signed, row.signedJson)
        assertEquals("""{"version":8}""", row.parsedJson)
        assertEquals(1_000L, row.updatedAt)
        repo.clear()
        assertNull(repo.roster.first())
    }

    @Test
    fun outboxInsertIgnoresDuplicateIdsAndKeepsCreationOrder() = runBlocking {
        val dao = db.outboxDao()
        fun row(id: String, created: Long, kind: OutboxKind = OutboxKind.CANCEL) =
            OutboxEntity(id, kind, "page", "", 0, 0, created)
        assertTrue(dao.insert(row("b", 2)) > 0)
        assertTrue(dao.insert(row("a", 1, OutboxKind.PAGE)) > 0)
        assertEquals(-1L, dao.insert(row("a", 9)))
        assertEquals(listOf("a", "b"), dao.all().map { it.id })
        assertEquals(OutboxKind.PAGE, dao.get("a")!!.kind)
        dao.reschedule("a", 3, 5_000)
        assertEquals(3, dao.get("a")!!.attempts)
        assertEquals(5_000L, dao.get("a")!!.nextAttemptAt)
        assertEquals(2, dao.observeCount().first())
        dao.delete("a")
        assertEquals(listOf("b"), dao.all().map { it.id })
    }

    @Test
    fun onlyOnePageIsActiveAtATime() = runBlocking {
        val repo = ActivePageRepository(db.activePageDao(), clock)
        assertNull(repo.current())
        repo.start("p1", PageRole.SENDER, "s1", "u1", "normal", "ringing")
        repo.start("p2", PageRole.RECEIVER, "s2", "u2", "urgent", "ringing")
        val active = repo.current()!!
        assertEquals("p2", active.pageId)
        assertEquals(PageRole.RECEIVER, active.role)
        assertEquals("s2", active.sessionId)
        assertEquals("u2", active.peerUserId)
        assertEquals("urgent", active.level)
        assertEquals(
            1,
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM active_page").use {
                it.moveToFirst()
                it.getInt(0)
            }
        )
        assertFalse(repo.updateState("p1", "ended"))
        assertTrue(repo.updateState("p2", "answered"))
        assertEquals("answered", repo.active.first()!!.state)
        repo.end("p2")
        assertNull(repo.current())
    }

    @Test
    fun schemaIsExportedAndCheckedIn() {
        // Gradle runs unit tests with the module directory as the working directory.
        val schema = File("schemas/com.example.ptt.data.PttDatabase/${PttDatabase.VERSION}.json")
        assertTrue("missing ${schema.absolutePath}", schema.isFile)
        val text = schema.readText()
        for (table in listOf("contacts", "roster", "outbox", "active_page")) {
            assertTrue(table, "\"tableName\": \"$table\"" in text)
        }
    }
}
