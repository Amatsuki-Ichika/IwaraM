plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

// Local definition of done: unit tests, lint and a debug build in one command.
tasks.register("verify") {
    group = "verification"
    description = "Runs the local definition of done: :app:test, :app:lintDebug, :app:assembleDebug."
    dependsOn(":app:test", ":app:lintDebug", ":app:assembleDebug")
}
