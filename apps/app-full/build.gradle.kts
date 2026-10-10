// app-full: Full Sideload MVP (targetSdk 28, minSdk 28, compileSdk 36).
// AGP 9.0+ built-in Kotlin: do NOT apply org.jetbrains.kotlin.android.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.legacy.kapt)
}

android {
    namespace = "dev.studiorizi.mterm.full"
    compileSdk = 36
    // ndkVersion pending: uncomment once the NDK is installed locally.
    // ndkVersion = "28.2.13676358"

    // PRoot binaries ship from distribution/proot/ (single source of truth,
    // version-pinned with SHA256SUMS) into generated assets at build time.
    // (Eager File: AGP 9 forbids Provider instances in the SourceSet API.)
    sourceSets {
        getByName("main").assets.srcDir(
            layout.buildDirectory.dir("generated/proot-assets").get().asFile,
        )
    }

    defaultConfig {
        applicationId = "dev.studiorizi.mterm.full"
        minSdk = 28
        targetSdk = 28
        versionCode = 10200
        versionName = "1.2.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "${rootDir}/mterm-upload-key.jks")
            storePassword = System.getenv("STORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS") ?: "upload"
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Sign with the release keystore only when credentials are present.
            if (System.getenv("STORE_PASSWORD") != null && System.getenv("KEY_PASSWORD") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            // Local development: default debug keystore.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Full is sideload-only by design (Play policy conflicts with apt/ELF).
    // ExpiredTargetSdkVersion lint is therefore not release-blocking here;
    // Play-bound Gates live in app-remote (target 36). Gate E still applies.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Core modules (safe design: UI reaches privileged backends only via use-cases).
    implementation(project(":core:session-core"))
    implementation(project(":core:terminal-emulator"))
    implementation(project(":core:pty-native"))
    implementation(project(":core:pty-runtime"))
    implementation(project(":core:terminal-session"))
    implementation(project(":core:process-supervisor"))
    implementation(project(":core:linux-core"))
    implementation(project(":core:linux-proot"))
    implementation(project(":core:linux-chroot"))
    implementation(project(":core:rootfs-manager"))
    implementation(project(":core:root-core"))
    implementation(project(":core:storage-mirror"))
    implementation(project(":core:android-bridge"))
    implementation(project(":core:data"))
    implementation(project(":core:diagnostics"))

    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.material3.windowsizeclass)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)

    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Copy version-pinned PRoot binaries from distribution/proot/ into assets.
// Runs before every asset merge so debug AND release APKs carry them.
val syncProotAssets by tasks.registering(Copy::class) {
    from("${rootDir}/distribution/proot") {
        include(
            "proot-arm64-v8a",
            "loader-arm64-v8a",
            "loader32-arm",
            "libtalloc.so.2",
            "libandroid-shmem.so",
        )
        rename("proot-arm64-v8a", "proot")
        rename("loader-arm64-v8a", "loader")
        rename("loader32-arm", "loader32")
    }
    into(layout.buildDirectory.dir("generated/proot-assets/bin"))
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(syncProotAssets)
}
