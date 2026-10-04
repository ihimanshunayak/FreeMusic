// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - Gradle build.
//
// This module is the desktop counterpart of :app. It shares the Android app's
// data-layer libraries (InnerTubeX, NewPipeExtractor, Rhino) rather than
// reimplementing them, and replaces the Android-only playback stack
// (androidx.media3) with libVLC through vlcj.

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose") version "1.10.3"
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // ---- UI: Compose Multiplatform (desktop) ----
    implementation(compose.desktop.currentOs)
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    implementation(compose.material3)
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation(compose.components.resources)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")

    // ---- UI: Fluent Design - Windows 11's own design language, for Compose ----
    // `fluent` gives the Windows-native control set (title bar, navigation view,
    // acrylic/mica surfaces, Fluent typography and motion), which is what makes
    // this build look like a first-class Windows app rather than a phone app in
    // a window. Apache-2.0, so it is compatible with this project's GPL-3.0.
    implementation("io.github.compose-fluent:fluent:v0.1.0")
    implementation("io.github.compose-fluent:fluent-icons-extended:v0.1.0")

    // ---- UI: Win32 integration ----
    // JNA reaches the shell APIs Compose has no binding for: DWM backdrop
    // (Mica/Acrylic), the taskbar, the media transport controls and the
    // notification centre. Windows-only at runtime; the code guards for that.
    implementation("net.java.dev.jna:jna:5.18.1")
    implementation("net.java.dev.jna:jna-platform:5.18.1")

    // ---- YouTube Music client: the pure-JVM KMP artifact of the same library
    //      the Android app uses (com.github.MetrolistGroup.innertubex:innertubex-android). ----
    implementation("com.github.MetrolistGroup.innertubex:innertubex-desktop:v0.7.4") {
        // quickjs-kt ships native Android .so files; desktop stream resolution
        // goes through NewPipeExtractor + Rhino instead, so it is not needed.
        exclude(group = "io.github.dokar3")
    }

    // ---- Networking (versions held at InnerTubeX's, which uses Ktor 3.5.2) ----
    implementation("io.ktor:ktor-client-core:3.5.2")
    implementation("io.ktor:ktor-client-okhttp:3.5.2")
    implementation("io.ktor:ktor-client-content-negotiation:3.5.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")

    // ---- Stream resolution: NewPipe solves YouTube's signature + `n` throttling ----
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")
    implementation("com.github.TeamNewPipe:nanojson:e9d656ddb49a412a5a0a5d5ef20ca7ef09549996")
    implementation("org.jsoup:jsoup:1.22.2")
    implementation("org.mozilla:rhino:1.8.1")
    implementation("org.mozilla:rhino-engine:1.8.1")

    // ---- Playback: libVLC through vlcj. ----
    implementation("uk.co.caprica:vlcj:4.8.3")

    // ---- Serialization / coroutines ----
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.json:json:20250517")

    // ---- Logging ----
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("org.slf4j:slf4j-simple:2.0.16")

    // ---- Tests ----
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

compose.desktop {
    application {
        mainClass = "com.ihimanshunayak.freemusic.desktop.MainKt"

        // The Compose plugin launches `run`, jpackage and jlink with the JVM
        // found here, defaulting to the Gradle JVM. That default is wrong for this
        // module: innertubex-desktop is published as Java 21 bytecode, so a Gradle
        // running on 17 produces UnsupportedClassVersionError (class file 65.0
        // against 61.0) before main() is even entered. Pointing it at the same
        // toolchain the compiler uses keeps run and packaging consistent, and
        // jpackage requires a JDK rather than a JRE in any case.
        javaHome = jdk21Home()

        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
            )
            packageName = "Free Music"
            packageVersion = "1.1.0"
            description = "Free Music - open-source desktop music player"
            vendor = "Himanshu Nayak"
            copyright = "Copyright (C) 2026 Himanshu Nayak. GPL-3.0-or-later."

            windows {
                menuGroup = "Free Music"
                upgradeUuid = "8f3c1d64-2b7e-4a19-9c05-6d1b8a7f2e30"
                shortcut = true
                dirChooser = true
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

/**
 * Absolute path to the JDK 21 toolchain this module targets.
 *
 * Resolved from Gradle rather than from `JAVA_HOME` so that the build does not
 * depend on how the developer's shell happens to be configured. The Foojay
 * resolver declared in `settings.gradle.kts` downloads a JDK on demand, so this
 * always succeeds even on a machine that has only an older JDK installed.
 */
fun jdk21Home(): String = javaToolchains
    .launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    .get()
    .metadata
    .installationPath
    .asFile
    .absolutePath

/**
 * Runs the live smoke check against the real YouTube Music service.
 *
 * This is deliberately outside `test` rather than part of it: it needs the
 * network, it depends on a third party staying up, and it mutates nothing but a
 * temporary cache directory. A red result here means "the upstream contract moved"
 * rather than "the build is broken", so it must never gate a commit.
 */
tasks.register<JavaExec>("smokeCheck") {
    group = "verification"
    description = "Exercises the real session, search, parser and stream resolver end to end."
    mainClass.set("com.ihimanshunayak.freemusic.desktop.LiveSmokeCheckKt")
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher.set(
        javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    )
}

/**
 * Drives libVLC through a real stream: resolve, play, seek, pause, resume, volume.
 *
 * Separate from `smokeCheck` because it adds a second prerequisite - a VLC
 * installation - on top of the network, and a failure here means "VLC is missing
 * or broke" rather than "the service changed". Run it with the VLC directory on
 * PATH, or with `VLC_PLUGIN_PATH` set for an unpacked build.
 */
tasks.register<JavaExec>("playbackCheck") {
    group = "verification"
    description = "Resolves a real track, plays it through libVLC and exercises the transport."
    mainClass.set("com.ihimanshunayak.freemusic.desktop.PlaybackSmokeCheckKt")
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher.set(
        javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    )
    // vlcj searches PATH and the standard install location; an unpacked VLC is
    // found through jna.library.path, which is only useful if it is set here.
    environment("VLC_PLUGIN_PATH", System.getenv("VLC_PLUGIN_PATH") ?: "")
}
