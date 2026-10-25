plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
}

// Lock every resolvable configuration (gradle.lockfile per project), including the build's own
// plugin classpath (buildscript-gradle.lockfile), so OSV-Scanner can read the exact versions.
buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
        // Build-tool transitives (AGP and its signing/publishing dependencies) pinned above versions
        // with known high/critical advisories. Keep in sync with the same list under allprojects.
        resolutionStrategy.eachDependency {
            // All Bouncy Castle artifacts must share one version (bcpkix calls bcprov internals).
            if (requested.group == "org.bouncycastle" && requested.name.endsWith("-jdk18on")) {
                useVersion("1.86")
            }
            when ("${requested.group}:${requested.name}") {
                "org.bitbucket.b_c:jose4j" -> useVersion("0.9.7")
                "org.jdom:jdom2" -> useVersion("2.0.6.1")
                "org.apache.commons:commons-lang3" -> useVersion("3.21.0")
            }
        }
    }
}

allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
    configurations.configureEach {
        // See the buildscript block: raise vulnerable transitives (Android Lint tool, AGP, Robolectric).
        resolutionStrategy.eachDependency {
            // All Bouncy Castle artifacts must share one version (bcpkix calls bcprov internals).
            if (requested.group == "org.bouncycastle" && requested.name.endsWith("-jdk18on")) {
                useVersion("1.86")
            }
            when ("${requested.group}:${requested.name}") {
                "org.bitbucket.b_c:jose4j" -> useVersion("0.9.7")
                "org.jdom:jdom2" -> useVersion("2.0.6.1")
                "org.apache.commons:commons-lang3" -> useVersion("3.21.0")
            }
        }
    }
}
