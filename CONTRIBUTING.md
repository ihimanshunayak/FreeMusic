# Contributing to Free Music

Thank you for contributing to Free Music. Follow these guidelines to ensure an efficient development and review process.

## Proposing Contributions
- Substantial features, refactors, or UI redesigns should normally be discussed before implementation. Open an issue on GitHub to outline your proposal.
- Straightforward bug fixes, documentation improvements, or minor corrections can be submitted directly as a Pull Request.

## Branch and Pull Request Workflow
Always check the repository for the current active version or development branch before starting work.

New work should normally be based on the latest active version or development branch rather than `main`, unless maintainers explicitly instruct otherwise. For example, during the `v1.6.x` release cycle, contributions branch from `v1.6.1`. As new release cycles begin, the target branch will advance accordingly.

When contributing:
- Fork the repository and create a descriptive branch (e.g., `fix/streaming-buffer`, `docs/translation-guide`).
- Keep pull requests focused on a single change or bug fix. Avoid mixing unrelated refactors, cosmetic tweaks, or mass reformatting with functional changes.
- Keep your branch reasonably synchronized with the target base branch.
- Provide a clear PR description explaining what was changed and why.
- Include relevant testing and validation details with your PR.
- Address review feedback promptly and keep discussions focused on technical merits.

## Development Setup
The project requires the following tools:
- **JDK**: Java Development Kit 17 (Eclipse Temurin 17 recommended).
- **Android SDK**: `compileSdk = 37`, `targetSdk = 36`, `minSdk = 26`. The
  `compileSdk` bump is forced by InnerTubeX's AAR; runtime behaviour stays at 36.
- **C/C++ NDK & CMake**: CMake 3.22.1+ and Android NDK (for native audio DSP components configured under `app/src/main/cpp`).
- **Listen Together Backend (Optional)**: Go 1.27+ if developing or testing the party server (`backend/`).

> [!IMPORTANT]
> **SDK Platform 37 install gotcha.** `sdkmanager` publishes this platform only as
> `platforms;android-37.0`, which lands in `platforms/android-37.0/` and reports
> `AndroidVersion.ApiLevel=37.0`. AGP 8.10.1 resolves `compileSdk = 37` to the
> legacy directory name `platforms/android-37/` and fails with
> `Failed to find target with hash string 'android-37'`. After installing, point
> the build at the platform it expects:
>
> ```bash
> # Windows: %LOCALAPPDATA%\Android\Sdk   macOS/Linux: ~/Android/Sdk
> cd "$ANDROID_HOME/platforms"
> cp -r android-37.0 android-37
> # then in android-37/source.properties set:
> #   AndroidVersion.ApiLevel=37      (instead of 37.0)
> # and in android-37/package.xml set:
> #   path="platforms;android-37"     and   <api-level>37</api-level>
> ```
>
> Write both files without a UTF-8 BOM — the SDK's XML parser rejects a leading BOM.

## Build and Test Commands
Run Gradle commands from the repository root.

Build:
```bash
./gradlew assembleDevDebug
```

Windows:
```powershell
.\gradlew.bat assembleDevDebug
```

Tests:
```bash
./gradlew testDevDebugUnitTest
```

Windows:
```powershell
.\gradlew.bat testDevDebugUnitTest
```

Run a specific test:
```bash
./gradlew testDevDebugUnitTest --tests "com.ihimanshunayak.freemusic.playback.audio.DirectAudioStreamingRegressionTest"
```

## Code Quality
- Follow idiomatic Kotlin and Jetpack Compose conventions.
- Maintain the project's existing architecture and separation of concerns (UI, domain logic, playback services, and data repositories).
- Write unit tests for new logic, fixes, and edge cases.
- Contributors should run relevant tests, must not introduce new failures, and should document known pre-existing failures when applicable.
- Keep diffs focused and minimal. Avoid unnecessary third-party dependencies.

## Audio Changes and Telemetry
For audio changes: application telemetry must not be presented as proof of physical hardware behavior unless that hardware behavior was actually verified.

Distinguish clearly between source metadata, decoder format, internal DSP format, AudioTrack output, AudioFlinger/HAL state, advertised device capability, and external physical endpoint behavior.

## Review Process
All pull requests require review and approval by repository maintainers before merging. Maintainers review changes for correctness, architecture fit, and maintainability.

## Dependencies and Licensing
Free Music is licensed under the **GNU General Public License v3.0 (GPLv3)**.

All contributed code and dependencies must be strictly compatible with GPLv3. Avoid introducing external dependencies unless strictly necessary; any new dependency must be evaluated for necessity, binary size, and license compliance.
