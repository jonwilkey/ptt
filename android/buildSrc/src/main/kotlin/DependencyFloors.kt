/**
 * Minimum versions for build-tool transitives (AGP, Android Lint, Robolectric) that carry known
 * high or critical advisories. A floor only ever RAISES a version: a request that is already at or
 * above its floor is left alone, so a future AGP that needs a newer library is never downgraded.
 * Remove an entry once the tool's own dependency has moved past the fixed version.
 */
object DependencyFloors {
    /**
     * Every Bouncy Castle `*-jdk18on` artifact has this floor, and [BouncyCastleAlignmentRule] makes them
     * all resolve to the same (highest) version, because bcpkix calls bcprov internals.
     */
    private const val BOUNCY_CASTLE_GROUP = "org.bouncycastle"
    private const val BOUNCY_CASTLE_SUFFIX = "-jdk18on"
    private const val BOUNCY_CASTLE_FLOOR = "1.86"

    private val floors = mapOf(
        "org.bitbucket.b_c:jose4j" to "0.9.7",
        "org.jdom:jdom2" to "2.0.6.1",
        "org.apache.commons:commons-lang3" to "3.21.0"
    )

    /** True for the Bouncy Castle artifacts that must all resolve to one version. */
    fun isBouncyCastle(group: String, name: String): Boolean =
        group == BOUNCY_CASTLE_GROUP && name.endsWith(BOUNCY_CASTLE_SUFFIX)

    /**
     * The version to force for [group]:[name] when [requested] is below its floor, or null when the
     * module has no floor or the request already meets it.
     */
    fun raisedVersion(group: String, name: String, requested: String?): String? {
        val floor = when {
            isBouncyCastle(group, name) -> BOUNCY_CASTLE_FLOOR
            else -> floors["$group:$name"]
        } ?: return null
        return if (requested.isNullOrBlank() || compareVersions(requested, floor) < 0) floor else null
    }

    /**
     * Compares dotted versions numerically segment by segment. A segment that is not a number (a
     * qualifier such as `rc1`) sorts below every number, and missing segments count as zero.
     */
    fun compareVersions(a: String, b: String): Int {
        val left = segments(a)
        val right = segments(b)
        for (i in 0 until maxOf(left.size, right.size)) {
            val cmp = left.getOrElse(i) { 0L }.compareTo(right.getOrElse(i) { 0L })
            if (cmp != 0) return cmp
        }
        return 0
    }

    private fun segments(version: String): List<Long> =
        version.split('.', '-', '+').map { it.toLongOrNull() ?: -1L }
}
