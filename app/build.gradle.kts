import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
}

val keystoreProperties = Properties().apply {
  val keystoreFile = rootProject.file("keystore.properties")
  if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) }
}
val releaseStorePath = keystoreProperties.getProperty("storeFile") ?: System.getenv("RELEASE_STORE_FILE")

android {
  namespace = "com.pedro.streamer"
  compileSdk = 37

  defaultConfig {
    applicationId = "com.pedro.streamer"
    minSdk = 23
    targetSdk = 37
    versionCode = project.version.toString().replace(".", "").toInt()
    versionName = project.version.toString()
    ndk {
      abiFilters += "arm64-v8a"
    }
  }
  signingConfigs {
    if (releaseStorePath != null) {
      create("release") {
        storeFile = rootProject.file(releaseStorePath)
        storePassword = keystoreProperties.getProperty("storePassword") ?: System.getenv("RELEASE_STORE_PASSWORD")
        keyAlias = keystoreProperties.getProperty("keyAlias") ?: System.getenv("RELEASE_KEY_ALIAS")
        keyPassword = keystoreProperties.getProperty("keyPassword") ?: System.getenv("RELEASE_KEY_PASSWORD")
      }
    }
  }
  buildTypes {
    debug {
      isDebuggable = true
    }
    release {
      isMinifyEnabled = false
      isDebuggable = false
      signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  buildFeatures {
    buildConfig = true
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation(project(":library"))
  implementation(libs.androidx.constraintlayout)
  implementation(libs.androidx.appcompat)
  implementation(libs.material)
  implementation(libs.okhttp)
}
