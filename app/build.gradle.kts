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

    val apiBaseUrl = providers.gradleProperty("BIKEGPS_API_BASE_URL").orElse("").get()
    buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    testInstrumentationRunner = "android.app.InstrumentationTestRunner"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }

  buildFeatures { buildConfig = true }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  testOptions { unitTests.isIncludeAndroidResources = false }
}

dependencies {
  testImplementation("junit:junit:4.13.2")
}
