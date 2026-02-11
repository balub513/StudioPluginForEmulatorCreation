plugins {
    id("org.jetbrains.kotlin.jvm") version "1.9.24"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
        intellijDependencies()
    }
}

dependencies {
    intellijPlatform {
        // Point this to your Android Studio installation
        local("/Applications/Android Studio.app/Contents")
        instrumentationTools()
    }
}

intellijPlatform {
//    androidStudio("2025.1.1")  // target Narwhal
    sandboxContainer.set(layout.projectDirectory.dir(".sandbox"))
    pluginConfiguration {
        name.set("Emulator Manager")
        version.set("1.2.0")
        ideaVersion {
            sinceBuild.set("251")
            untilBuild.set("999.*")
        }
    }
    sandboxContainer.set(layout.projectDirectory.dir(".sandbox"))
}

// Disable tasks we don’t need (avoids build errors)
tasks {
    buildSearchableOptions { enabled = false }
    prepareJarSearchableOptions { enabled = false }
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }
}
