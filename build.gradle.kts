import java.io.FileOutputStream
import java.net.URL

plugins {
    id("com.dorongold.task-tree") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.noarg") apply false
}

val projectVersion: String by project
val skywalkingVersion: String by project

version = projectVersion

subprojects {
    rootProject.properties.forEach {
        if (it.key.endsWith("Version") && it.value != null) {
            project.ext.set(it.key, it.value)
        }
    }

    configurations.all {
        resolutionStrategy.dependencySubstitution {
            substitute(module("com.github.sourceplusplus.protocol:protocol"))
                .using(project(":protocol"))
            substitute(module("com.github.sourceplusplus.interface-portal:portal-jvm"))
                .using(project(":interfaces:portal"))
            substitute(module("com.github.sourceplusplus:processor-dependencies"))
                .using(project(":processors:dependencies"))
        }
    }

    tasks.withType<JavaCompile> {
        sourceCompatibility = "1.8"
        targetCompatibility = "1.8"
    }
}

tasks {
    register("buildDist") {
        //todo: use gradle copy task
        dependsOn(":platform:build")
        doLast {
            file("dist/spp-platform-$projectVersion/config").mkdirs()
            file("config/spp-platform.yml")
                .copyTo(file("dist/spp-platform-$projectVersion/config/spp-platform.yml"))
            file("platform/build/libs/spp-platform-$projectVersion.jar")
                .copyTo(file("dist/spp-platform-$projectVersion/spp-platform-$projectVersion.jar"))
        }
    }
    register<Tar>("makeDist") {
        dependsOn(":buildDist")
        mustRunAfter(":buildDist")

        into("spp-platform-${project.version}") {
            from("dist/spp-platform-${project.version}")
        }
        destinationDirectory.set(file("dist"))
        compression = Compression.GZIP
        archiveFileName.set("spp-platform-${project.version}.tar.gz")
    }

    register("downloadSkywalking") {
        doLast {
            val f = File(projectDir, "docker/e2e/apache-skywalking-apm-$skywalkingVersion.tar.gz")
            if (!f.exists()) {
                println("Downloading Apache SkyWalking")
                URL("https://downloads.apache.org/skywalking/$skywalkingVersion/apache-skywalking-apm-$skywalkingVersion.tar.gz")
                    .openStream().use { input ->
                        FileOutputStream(f).use { output ->
                            input.copyTo(output)
                        }
                    }
                println("Downloaded Apache SkyWalking")
            }
        }
    }
}
