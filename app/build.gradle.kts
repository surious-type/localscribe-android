import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val configuredTestRunner =
    providers.gradleProperty("testRunner").getOrElse("androidx.test.runner.AndroidJUnitRunner")

android {
    namespace = "io.github.surioustype.localscribe"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.surioustype.localscribe"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = configuredTestRunner

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }
    }

    ndkVersion = "30.0.16248370"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
        }
        create("play") {
            dimension = "distribution"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            allWarningsAsErrors.set(true)
            progressiveMode.set(true)
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        animationsDisabled = true
    }

    sourceSets {
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

// AGP 9 built-in Kotlin does not expose Android Kotlin source-set tasks to the
// ktlint Gradle plugin. The plugin resolves KtLint 1.5.0, so use that pinned
// CLI directly and pass every checked-in app Kotlin source to an explicit gate.
val appKotlinSources =
    fileTree("src") {
        include("**/*.kt")
        exclude("**/build/**", "**/generated/**")
    }

val appKtlintCli by configurations.creating

fun org.gradle.api.tasks.JavaExec.configureAppKtlint(format: Boolean) {
    classpath = appKtlintCli
    mainClass.set("com.pinterest.ktlint.Main")
    inputs.file(rootProject.file(".editorconfig")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(appKotlinSources).withPathSensitivity(PathSensitivity.RELATIVE)
    notCompatibleWithConfigurationCache(
        "Discovers Kotlin files below app/src at configuration time.",
    )
    args(
        buildList {
            if (format) add("--format")
            add("--relative")
            addAll(
                appKotlinSources.files
                    .sortedBy { it.relativeTo(projectDir).path }
                    .map { it.relativeTo(projectDir).path },
            )
        },
    )
}

val appKtlintCheck by tasks.registering(org.gradle.api.tasks.JavaExec::class) {
    group = "verification"
    description = "Checks every Kotlin file below app/src with the configured ktlint engine."
    configureAppKtlint(format = false)
}

tasks.register<org.gradle.api.tasks.JavaExec>("appKtlintFormat") {
    group = "formatting"
    description =
        "Formats every Kotlin file below app/src; run only with source-owner coordination."
    configureAppKtlint(format = true)
}

tasks.named("ktlintCheck") {
    dependsOn(appKtlintCheck)
}

dependencies {
    add("appKtlintCli", "com.pinterest.ktlint:ktlint-cli:1.5.0:all")

    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
