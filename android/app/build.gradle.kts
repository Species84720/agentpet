plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android { namespace = "online.thenightwatcher.agentpet"; compileSdk = 35
    defaultConfig { applicationId = "online.thenightwatcher.agentpet"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "0.1.0" }
    // This is intentionally a checked-in development key, not a production
    // release key. GitHub runners are ephemeral; using the default debug key
    // would create a new signer on every run and make Android reject updates.
    signingConfigs {
        create("development") {
            storeFile = rootProject.file("agentpet-development.keystore")
            storePassword = "agentpet-development"
            keyAlias = "agentpet"
            keyPassword = "agentpet-development"
        }
    }
    buildTypes { getByName("debug") { signingConfig = signingConfigs.getByName("development") } }
    kotlinOptions { jvmTarget = "1.8" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
