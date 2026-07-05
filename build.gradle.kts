// Root build. Versions are pinned to something recent that supports compileSdk 36 (Android 16).
// If Gradle sync complains about the Android Gradle Plugin not supporting API 36,
// bump these to the latest AGP/Kotlin and set a matching Gradle version in
// gradle/wrapper/gradle-wrapper.properties. The repro does not depend on exact versions.
plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}
