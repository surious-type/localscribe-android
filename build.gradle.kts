plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktlint)
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
}

tasks.register("staticAnalysis") {
    group = "verification"
    description = "Runs formatting checks and Android static analysis."
    dependsOn(subprojects.map { "${it.path}:ktlintCheck" })
    dependsOn(":app:lintGithubDebug")
}
