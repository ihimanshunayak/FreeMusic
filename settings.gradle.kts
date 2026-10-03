pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
// The desktop module targets JVM 21 (InnerTubeX's KMP artifact is Java 21
// bytecode). This lets Gradle fetch a matching JDK on machines that only ship
// an older one, instead of requiring a hand-installed toolchain.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor is published via JitPack.
        maven("https://jitpack.io")
    }
}

rootProject.name = "FreeMusic"
include(":app")
include(":desktop")
 