package com.example.ptt.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A person from `GET /v1/contacts`. [presence] is `ok` | `warning` | `offline`. */
@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    val admin: Boolean,
    val presence: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

/**
 * The family roster, a single row ([ROW_ID]). [signedJson] is the signed object exactly as received
 * (so it can be re-verified and forwarded byte for byte); [parsedJson] is the verified payload
 * JSON, written by the strict roster parser (a later PR), never by this layer.
 */
@Entity(tableName = "roster")
data class RosterEntity(
    @PrimaryKey val id: Int = ROW_ID,
    val version: Long,
    @ColumnInfo(name = "signed_json") val signedJson: String,
    @ColumnInfo(name = "parsed_json") val parsedJson: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
) {
    companion object {
        const val ROW_ID = 1
    }
}

enum class OutboxKind(val path: String) {
    PAGE("pages"),
    DELIVERED("delivered"),
    ACKNOWLEDGE("acknowledge"),
    ANSWER("answer"),
    CANCEL("cancel")
}

/**
 * A request waiting to be sent. [id] is the idempotency key (for a page it is the page id, so
 * enqueueing the same page twice is a no-op); [body] is the JSON request body (empty for the
 * bodyless page actions).
 */
@Entity(tableName = "outbox", indices = [Index("created_at")])
data class OutboxEntity(
    @PrimaryKey val id: String,
    val kind: OutboxKind,
    @ColumnInfo(name = "page_id") val pageId: String,
    val body: String,
    val attempts: Int,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

enum class PageRole { SENDER, RECEIVER }

/**
 * The one page or session this phone is involved in right now. Keyed by page id, but the
 * repository keeps at most one row. [level] is `normal` | `urgent`; [state] follows the protocol
 * (`ringing`, `acknowledged`, `answered`, `ended`, ...).
 */
@Entity(tableName = "active_page")
data class ActivePageEntity(
    @PrimaryKey @ColumnInfo(name = "page_id") val pageId: String,
    val role: PageRole,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "peer_user_id") val peerUserId: String,
    val level: String,
    val state: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
