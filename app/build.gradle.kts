plugins {
  alias(libs.plugins.android.application)
}

android {
  namespace = "com.pedro.streamer"
  compileSdk = 37

  defaultConfig {
    applicationId = "com.pedro.streamer"
    minSdk = 21
    targetSdk = 37
    versionCode = project.version.toString().replace(".", "").toInt()
    versionName = project.version.toString()
    ndk {
      abiFilters += "arm64-v8a"
    }
  }
  buildTypes {
    debug {
      isDebuggable = true
    }
    release {
      isMinifyEnabled = false
      isDebuggable = false
      signingConfig = signingConfigs.getByName("debug")
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
