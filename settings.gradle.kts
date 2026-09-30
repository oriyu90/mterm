pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "mterm"

include(":apps:app-full")
include(":apps:app-modern")
include(":apps:app-remote")
include(":core:session-core")
include(":core:terminal-emulator")
include(":core:pty-native")
include(":core:process-supervisor")
include(":core:linux-core")
include(":core:linux-proot")
include(":core:linux-chroot")
include(":core:rootfs-manager")
include(":core:root-core")
include(":core:storage-mirror")
include(":core:android-bridge")
include(":core:data")
include(":core:diagnostics")
