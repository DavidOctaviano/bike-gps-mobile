plugins { id("com.android.application") }

android {
  namespace = "com.bikegps.companion"
  compileSdk = 35

  defaultConfig {
    applicationId = "com.bikegps.companion"
    minSdk = 26
    targetSdk = 35
    versionCode = 4
    versionName = "0.3.0"

    fun setting(name: String): String = providers.gradleProperty(name)
      .orElse(providers.environmentVariable(name)).orElse("").get()
    fun quoted(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    val apiBaseUrl = setting("BIKEGPS_API_BASE_URL")
    val mapboxAccessToken = setting("MAPBOX_ACCESS_TOKEN")
    val activityTilesUrl = setting("BIKEGPS_ACTIVITY_TILES_URL")
    val activityTilesLayer = setting("BIKEGPS_ACTIVITY_TILES_LAYER").ifBlank { "activity" }
    buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    buildConfigField("String", "MAPBOX_ACCESS_TOKEN", quoted(mapboxAccessToken))
    buildConfigField("String", "ACTIVITY_TILES_URL", quoted(activityTilesUrl))
    buildConfigField("String", "ACTIVITY_TILES_LAYER", quoted(activityTilesLayer))
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
  implementation("com.mapbox.maps:android-ndk27:11.29.1")
  testImplementation("junit:junit:4.13.2")
}
