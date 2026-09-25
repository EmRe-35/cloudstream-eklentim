import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("com.lagradost.cloudstream3:gradle:local-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
    }
}

allprojects {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(
    configuration: CloudstreamExtension.() -> Unit
) {
    extensions
        .getByName<CloudstreamExtension>("cloudstream")
        .configuration()
}

fun Project.android(
    configuration: BaseExtension.() -> Unit
) {
    extensions
        .getByName<BaseExtension>("android")
        .configuration()
}

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(
            System.getenv("GITHUB_REPOSITORY")
                ?: "EmRe-35/cloudstream-eklentim"
        )
    }

    android {
        namespace = "com.example"

        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8)

                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions",
                    "-Xskip-metadata-version-check"
                )
            }
        }
    }

    dependencies {
        val cloudstream by configurations

        cloudstream(
            "com.lagradost:cloudstream3:pre-release"
        )

        add(
            "implementation",
            kotlin("stdlib")
        )

        add(
            "implementation",
            "com.github.Blatzar:NiceHttp:0.4.11"
        )

        add(
            "implementation",
            "org.jsoup:jsoup:1.18.3"
        )

        add(
            "implementation",
            "com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1"
        )
    }
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}



