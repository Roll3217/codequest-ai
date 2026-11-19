// Top-level build file where you can add configuration options common to all sub-projects/modules.
import java.util.Base64

plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.google.devtools.ksp) apply false
  alias(libs.plugins.secrets) apply false
  alias(libs.plugins.google.services) apply false
}

// Automatically materialize debug.keystore from debug.keystore.base64 when running locally in Android Studio
val debugKeystoreFile = rootProject.file("debug.keystore")
val debugKeystoreBase64File = rootProject.file("debug.keystore.base64")
if (!debugKeystoreFile.exists() && debugKeystoreBase64File.exists()) {
  runCatching {
    val sanitizedBase64 = debugKeystoreBase64File.readText().replace("\\s+".toRegex(), "")
    debugKeystoreFile.writeBytes(Base64.getDecoder().decode(sanitizedBase64))
  }
}
