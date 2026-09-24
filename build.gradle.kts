plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "am.ghi"
version = "0.1.2"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        val localIde = providers.gradleProperty("localIde")
        if (localIde.isPresent) local(localIde.get()) else goland("2025.1.7.2")
        bundledPlugin("org.jetbrains.plugins.go")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        pluginVerifier()
    }
    testImplementation("junit:junit:4.13.2")
}

java { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
intellijPlatform {
    pluginConfiguration {
        ideaVersion { sinceBuild = "251" }
    }
    pluginVerification {
        ides {
            val localIde = providers.gradleProperty("localIde")
            if (localIde.isPresent) local(localIde.get()) else current()
        }
    }
}
tasks.test { systemProperty("java.awt.headless", "true") }
