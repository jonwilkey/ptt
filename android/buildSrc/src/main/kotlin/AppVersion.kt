/** Parses `android/version.txt` (maintained by release-please) into the app's version fields. */
data class AppVersion(val major: Int, val minor: Int, val patch: Int) {
    val name: String get() = "$major.$minor.$patch"

    /** major * 1_000_000 + minor * 1_000 + patch; minor and patch must stay below 1000. */
    val code: Int get() = major * 1_000_000 + minor * 1_000 + patch

    companion object {
        private val FORMAT = Regex("""(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})""")

        /** Accepts only `MAJOR.MINOR.PATCH` (surrounding whitespace allowed); anything else fails. */
        fun parse(text: String): AppVersion {
            val match = requireNotNull(FORMAT.matchEntire(text.trim())) {
                "android/version.txt must contain MAJOR.MINOR.PATCH (each 0-999), found: '${text.trim()}'"
            }
            val (major, minor, patch) = match.destructured
            return AppVersion(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}
