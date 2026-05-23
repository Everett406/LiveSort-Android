import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.ui)
    implementation(compose.uiTooling)
}

compose.desktop {
    application {
        mainClass = "com.livesort.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Exe)
            packageName = "LiveSort"
            packageVersion = "1.0.0"
            description = "LiveSort Desktop - Song auto-sorting and seamless transition"
            copyright = "© 2024 LiveSort"

            windows {
                menuGroup = "LiveSort"
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
            }

            macOS {
                bundleID = "com.livesort.desktop"
            }

            linux {
                menuGroup = "Audio"
            }
        }
    }
}
