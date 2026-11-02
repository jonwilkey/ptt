package com.example.ptt.talk

import android.util.Log
import com.example.ptt.crypto.DeviceKeyStore
import com.example.ptt.crypto.DeviceKeys
import com.example.ptt.crypto.DeviceKeysUnavailable
import com.example.ptt.crypto.RecoveryResult
import com.example.ptt.crypto.SenderKeys
import com.example.ptt.net.ApiResult
import com.example.ptt.net.DeviceTokenStore
import com.example.ptt.net.PttApi
import com.example.ptt.push.SessionKeys

/** Gets a sender its session key back after its process died (docs/protocol.md section 13.7). */
fun interface SenderKeyRecovery {
    /**
     * Fetches [pageId], checks it with the device's own keys and puts the session key, the sender key and the
     * peers in memory. Returns the page's session id, or null when the sender cannot take part. One attempt.
     */
    suspend fun recover(pageId: String): String?
}

class SenderRecovery(
    private val api: PttApi,
    private val keyStore: DeviceKeyStore,
    private val tokens: DeviceTokenStore,
    private val sessionKeys: SessionKeys
) : SenderKeyRecovery {
    @Suppress("ReturnCount")
    override suspend fun recover(pageId: String): String? {
        val self = tokens.deviceId() ?: return null
        val keys = try {
            DeviceKeys.load(keyStore)
        } catch (_: DeviceKeysUnavailable) {
            return null
        } catch (e: IllegalStateException) {
            Log.w(TAG, "recovery failed: ${e.javaClass.simpleName}")
            return null
        }
        val page = (api.getPage(pageId) as? ApiResult.Success)?.body ?: return null
        val result = SenderKeys.recover(self, keys.ed25519Public, keys, pageId, page.envelope, page.senderKey)
        if (result !is RecoveryResult.Recovered) return null
        sessionKeys.put(pageId, result.sessionKey)
        sessionKeys.putPeers(pageId, result.envelope.keys.keys - self)
        sessionKeys.putSenderKey(pageId, result.senderKey)
        return result.envelope.sessionId
    }

    private companion object {
        const val TAG = "SenderRecovery"
    }
}
