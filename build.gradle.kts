plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

// What CI runs (.github/workflows/ci.yml): `./gradlew verify`. The relay's tests are separate:
// `npm --prefix relay test`.
tasks.register("verify") {
    group = "verification"
    description = "Runs the engine's tests, the app's unit tests and lint, and builds a debug APK."
    dependsOn(":engine:test", ":app:testDebugUnitTest", ":app:lintDebug", ":app:assembleDebug")
}
