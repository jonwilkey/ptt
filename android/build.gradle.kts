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
    dependencies {
        components { all<BouncyCastleAlignmentRule>() }
    }
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
        // Build-tool transitives (AGP and its signing/publishing dependencies) raised to the floors in
        // buildSrc/DependencyFloors.kt; the same rule applies to every project configuration below.
        resolutionStrategy.eachDependency {
            DependencyFloors.raisedVersion(requested.group, requested.name, requested.version)?.let(::useVersion)
        }
    }
}

allprojects {
    dependencies {
        components { all<BouncyCastleAlignmentRule>() }
    }
    dependencyLocking {
        lockAllConfigurations()
    }
    configurations.configureEach {
        // Raise vulnerable transitives (Android Lint tool, AGP, Robolectric); never downgrade.
        resolutionStrategy.eachDependency {
            DependencyFloors.raisedVersion(requested.group, requested.name, requested.version)?.let(::useVersion)
        }
    }
}
