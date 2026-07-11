plugins {
    java
    id("com.gradleup.shadow") version "8.3.0"
}

group = "net.justempire"
version = "1.5.0"

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://oss.sonatype.org/content/groups/public/")
    maven("https://m2.dv8tion.net/releases")
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.21.11-R0.1-SNAPSHOT")

    // Bundled into the shaded jar
    implementation("net.dv8tion:JDA:6.3.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.0")
    implementation("org.xerial:sqlite-jdbc:3.51.1.0")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks {
    processResources {
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }
    shadowJar {
        archiveClassifier.set("")
    }
    build {
        dependsOn(shadowJar)
    }
}
