import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppVersionTest {
    @Test
    fun versionCodeCombinesMajorMinorPatch() {
        assertEquals(0, AppVersion.parse("0.0.0").code)
        assertEquals(1_002_003, AppVersion.parse("1.2.3").code)
        assertEquals(12_034_056, AppVersion.parse("12.34.56").code)
        assertEquals(999_999_999, AppVersion.parse("999.999.999").code)
    }

    @Test
    fun nameIsTheParsedVersion() {
        assertEquals("0.1.0", AppVersion.parse("0.1.0\n").name)
    }

    @Test
    fun malformedVersionsAreRejected() {
        listOf("", "1", "1.2", "1.2.3.4", "v1.2.3", "1.2.3-rc1", "01.2.3", "1.2.1000", "a.b.c").forEach {
            assertThrows("'$it'", IllegalArgumentException::class.java) { AppVersion.parse(it) }
        }
    }
}
