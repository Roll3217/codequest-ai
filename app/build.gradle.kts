import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.util.Base64

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

// Automatically generate debug.keystore if missing on local machine (e.g. Windows Android Studio)
val fallbackDebugKeystoreBase64 =
  "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"

val localDebugKeystore = file("${rootDir}/debug.keystore")
val localDebugKeystoreBase64 = file("${rootDir}/debug.keystore.base64")
if (!localDebugKeystore.exists()) {
  runCatching {
    val cleanBase64 =
      if (localDebugKeystoreBase64.exists()) {
        localDebugKeystoreBase64.readText().replace("\\s+".toRegex(), "")
      } else {
        fallbackDebugKeystoreBase64
      }
    localDebugKeystore.parentFile?.mkdirs()
    localDebugKeystore.writeBytes(Base64.getDecoder().decode(cleanBase64))
  }
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.codequestai.app"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug { signingConfig = signingConfigs.getByName("debugConfig") }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    viewBinding = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

dependencies {
  // Core AndroidX & Material 3 Views (No Jetpack Compose per project requirements)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.appcompat)
  implementation(libs.google.material)
  implementation(libs.androidx.constraintlayout)
  implementation(libs.androidx.fragment.ktx)
  implementation(libs.androidx.activity.ktx)
  implementation(libs.androidx.viewpager2)
  implementation(libs.androidx.recyclerview)

  // Lifecycle & ViewModel
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.ktx)
  implementation(libs.androidx.lifecycle.livedata.ktx)

  // Room Database for structured local persistence
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  "ksp"(libs.androidx.room.compiler)

  // Coroutines
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)

  // Networking (Backend API ready)
  implementation(libs.retrofit)
  implementation(libs.converter.moshi)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.logging.interceptor)
  "ksp"(libs.moshi.kotlin.codegen)

  // Unused template dependencies commented out for optimal APK size
  // implementation(platform(libs.androidx.compose.bom))
  // implementation(platform(libs.firebase.bom))
  // implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.compose.material3)

  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
}
