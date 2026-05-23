plugins {
    // Root project does not apply plugins directly
    // Plugins are applied in subprojects
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}
