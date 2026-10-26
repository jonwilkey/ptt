package com.example.ptt.data

import com.example.ptt.net.ContactDto
import java.time.Clock
import kotlinx.coroutines.flow.Flow

/** Contacts for the UI; refreshed from `GET /v1/contacts`. */
class ContactRepository(private val dao: ContactDao, private val clock: Clock) {
    val contacts: Flow<List<ContactEntity>> = dao.observeAll()

    suspend fun replaceAll(users: List<ContactDto>) {
        val now = clock.millis()
        dao.replaceAll(users.map { ContactEntity(it.id, it.displayName, it.admin, it.presence, now) })
    }

    suspend fun clear() = dao.clear()
}

/**
 * The stored roster. This layer never parses or verifies it: the strict parser and signature check
 * (crypto) produce [parsedJson] and [version], and only a verified roster should be saved.
 */
class RosterRepository(private val dao: RosterDao, private val clock: Clock) {
    val roster: Flow<RosterEntity?> = dao.observe()

    suspend fun current(): RosterEntity? = dao.get()

    suspend fun save(version: Long, signedJson: String, parsedJson: String) {
        dao.put(
            RosterEntity(
                version = version,
                signedJson = signedJson,
                parsedJson = parsedJson,
                updatedAt = clock.millis()
            )
        )
    }

    suspend fun clear() = dao.clear()
}

/** The one active page or session ("one at a time"). */
class ActivePageRepository(private val dao: ActivePageDao, private val clock: Clock) {
    val active: Flow<ActivePageEntity?> = dao.observe()

    suspend fun current(): ActivePageEntity? = dao.get()

    /** Makes this page the active one, replacing any other. */
    @Suppress("LongParameterList")
    suspend fun start(
        pageId: String,
        role: PageRole,
        sessionId: String,
        peerUserId: String,
        level: String,
        state: String
    ) {
        val now = clock.millis()
        dao.replace(ActivePageEntity(pageId, role, sessionId, peerUserId, level, state, now, now))
    }

    /** Updates the state of [pageId] if it is the active page; returns whether it was. */
    suspend fun updateState(pageId: String, state: String): Boolean {
        val page = dao.get()?.takeIf { it.pageId == pageId } ?: return false
        return dao.update(page.copy(state = state, updatedAt = clock.millis())) > 0
    }

    suspend fun end(pageId: String) = dao.delete(pageId)

    suspend fun clear() = dao.clear()
}
