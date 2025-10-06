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
    pluginConfiguration {
        name.set("Emulator Manager")
        version.set("1.0.0")
        ideaVersion {
            sinceBuild.set("242")
            untilBuild.set("242.*")
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
