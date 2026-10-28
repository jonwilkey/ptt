import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DependencyFloorsTest {
    @Test
    fun belowTheFloorIsRaised() {
        assertEquals("1.86", DependencyFloors.raisedVersion("org.bouncycastle", "bcprov-jdk18on", "1.79"))
        assertEquals("1.86", DependencyFloors.raisedVersion("org.bouncycastle", "bcpkix-jdk18on", "1.9"))
        assertEquals("3.21.0", DependencyFloors.raisedVersion("org.apache.commons", "commons-lang3", "3.12.0"))
        assertEquals("2.0.6.1", DependencyFloors.raisedVersion("org.jdom", "jdom2", "2.0.6"))
        assertEquals("33.7.2-jre", DependencyFloors.raisedVersion("com.google.guava", "guava", "33.4.0-jre"))
        assertEquals("33.7.2-jre", DependencyFloors.raisedVersion("com.google.guava", "guava", "33.6.0-jre"))
    }

    @Test
    fun atOrAboveTheFloorIsNeverDowngraded() {
        assertNull(DependencyFloors.raisedVersion("org.bouncycastle", "bcprov-jdk18on", "1.86"))
        assertNull(DependencyFloors.raisedVersion("org.bouncycastle", "bcprov-jdk18on", "1.87"))
        assertNull(DependencyFloors.raisedVersion("org.bouncycastle", "bcutil-jdk18on", "2.0"))
        assertNull(DependencyFloors.raisedVersion("org.apache.commons", "commons-lang3", "3.21.1"))
        assertNull(DependencyFloors.raisedVersion("org.bitbucket.b_c", "jose4j", "0.10.0"))
        assertNull(DependencyFloors.raisedVersion("com.google.guava", "guava", "33.7.2-jre"))
        assertNull(DependencyFloors.raisedVersion("com.google.guava", "guava", "33.8.0-jre"))
    }

    @Test
    fun unversionedRequestsGetTheFloor() {
        assertEquals("0.9.7", DependencyFloors.raisedVersion("org.bitbucket.b_c", "jose4j", null))
        assertEquals("0.9.7", DependencyFloors.raisedVersion("org.bitbucket.b_c", "jose4j", ""))
    }

    @Test
    fun otherModulesAreUntouched() {
        assertNull(DependencyFloors.raisedVersion("org.bouncycastle", "bcprov-jdk15on", "1.0"))
        assertNull(DependencyFloors.raisedVersion("com.example", "anything", "0.0.1"))
    }

    @Test
    fun versionsCompareNumerically() {
        assertEquals(-1, DependencyFloors.compareVersions("1.9", "1.10"))
        assertEquals(0, DependencyFloors.compareVersions("1.86", "1.86.0"))
        assertEquals(1, DependencyFloors.compareVersions("1.86.1", "1.86"))
        assertEquals(-1, DependencyFloors.compareVersions("1.86-rc1", "1.86"))
    }
}
