allprojects {
  group = "com.github.BlueScorpioVR"
  version = "0.1.0"

  plugins.withType<PublishingPlugin> {
    configure<com.android.build.api.dsl.LibraryExtension> {
      compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
      }
    }
    configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension> {
      jvmToolchain(17)
      coreLibrariesVersion = "2.2.21"
      compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
      }
    }
    configure<PublishingExtension> {
      publications.withType<MavenPublication>().all {
        pom {
          name = "ScreenStreamerXR"
          description = "Android XR screen streamer. Based on RootEncoder by pedroSG94 (https://github.com/pedroSG94/RootEncoder)."
          url = "https://github.com/BlueScorpioVR/ScreenStreamerXR"
          licenses {
            license {
              name = "Apache-2.0"
              url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
              distribution = "manual"
            }
          }
        }
      }
    }
  }
}

plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.jetbrains.kotlin) apply false
  alias(libs.plugins.jetbrains.dokka) apply true
}

dependencies {
  dokka(project(":common"))
  dokka(project(":encoder"))
  dokka(project(":library"))
  dokka(project(":rtmp"))
  dokka(project(":rtsp"))
  dokka(project(":srt"))
  dokka(project(":udp"))
}

tasks.named<org.jetbrains.dokka.gradle.tasks.DokkaGeneratePublicationTask>("dokkaGeneratePublicationHtml") {
  outputDirectory.set(layout.projectDirectory.dir("docs"))
}