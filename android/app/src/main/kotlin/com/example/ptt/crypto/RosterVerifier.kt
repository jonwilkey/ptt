package com.example.ptt.crypto

/** An admin the phone trusts before it holds any roster: from the enrollment QR, or itself at bootstrap. */
class TrustedAdmin(val id: String, val ed25519: ByteArray)

/** This device's own identity, which the first roster after enrollment must list with the same keys. */
class DeviceIdentity(val deviceId: String, val ed25519: ByteArray, val x25519: ByteArray)

enum class RosterRejection {
    /** The signer is not an admin of the current roster (or not one of the trusted admins). */
    UNTRUSTED_SIGNER,
    BAD_SIGNATURE,
    MALFORMED_PAYLOAD,
    STALE_VERSION,

    /** First roster: it does not list the signing admin with the trusted key. */
    SIGNER_NOT_LISTED,

    /** First roster: it does not list this device with its own keys. */
    SELF_NOT_LISTED,

    /** First roster but no trusted admin or no own identity was supplied. */
    NO_TRUST_ANCHOR
}

sealed interface RosterDecision {
    class Accepted(val roster: Roster) : RosterDecision
    class Rejected(val reason: RosterRejection) : RosterDecision
}

/** The ordered acceptance rules a phone applies to a roster (docs/protocol.md sections 4 and 5). */
object RosterVerifier {
    /**
     * Decides whether [candidate] becomes the phone's roster.
     *
     * With a [current] roster: the signer must be an admin in it, the signature must verify under that
     * entry's key, the payload must be well-formed, and `version` must be higher than the current one.
     *
     * With no roster ([current] null, right after enrolling or at bootstrap): the signer must be one of
     * [trustedAdmins] (the QR's admin keys, or this device itself at bootstrap), and the roster must
     * list that admin with the same key and [self] with its own keys. The version check starts over.
     */
    // Each rule of the ordered acceptance list rejects at its own step.
    @Suppress("ReturnCount")
    fun accept(
        current: Roster?,
        trustedAdmins: List<TrustedAdmin>,
        self: DeviceIdentity?,
        candidate: SignedObject
    ): RosterDecision {
        val signerKey = signerKey(current, trustedAdmins, candidate.signer)
            ?: return reject(
                if (current == null && (trustedAdmins.isEmpty() || self == null)) {
                    RosterRejection.NO_TRUST_ANCHOR
                } else {
                    RosterRejection.UNTRUSTED_SIGNER
                }
            )
        val message = SignedObject.message(Contexts.ROSTER, candidate.payload)
        if (!TinkKeys.verifyEd25519(signerKey, message, candidate.sig)) return reject(RosterRejection.BAD_SIGNATURE)
        val roster = try {
            Roster.parse(candidate.payload)
        } catch (_: ProtocolException) {
            return reject(RosterRejection.MALFORMED_PAYLOAD)
        }
        val failure = when {
            current != null -> if (roster.version > current.version) null else RosterRejection.STALE_VERSION
            else -> firstRosterFailure(roster, signerKey, candidate.signer, self)
        }
        return if (failure == null) RosterDecision.Accepted(roster) else reject(failure)
    }

    private fun reject(reason: RosterRejection): RosterDecision = RosterDecision.Rejected(reason)

    /** The key that must have signed: the signer's admin entry in [current], else its trusted key. */
    private fun signerKey(current: Roster?, trusted: List<TrustedAdmin>, signer: String): ByteArray? =
        if (current != null) {
            current.find(signer)?.takeIf { it.admin }?.ed25519
        } else {
            trusted.firstOrNull { it.id == signer }?.ed25519
        }

    private fun firstRosterFailure(
        roster: Roster,
        signerKey: ByteArray,
        signerId: String,
        self: DeviceIdentity?
    ): RosterRejection? {
        val listed = roster.find(signerId)
        val own = self?.let { roster.find(it.deviceId) }
        return when {
            self == null -> RosterRejection.NO_TRUST_ANCHOR

            listed == null || !listed.admin || !listed.ed25519.contentEquals(signerKey) ->
                RosterRejection.SIGNER_NOT_LISTED

            own == null || !own.ed25519.contentEquals(self.ed25519) || !own.x25519.contentEquals(self.x25519) ->
                RosterRejection.SELF_NOT_LISTED

            else -> null
        }
    }
}
