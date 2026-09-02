plugins { id("com.android.application") }

android {
  namespace = "com.bikegps.companion"
  compileSdk = 35

  defaultConfig {
    applicationId = "com.bikegps.companion"
    minSdk = 26
    targetSdk = 35
    versionCode = 1
    versionName = "0.1.0"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
    }
  }
}

