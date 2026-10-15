import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from keystore.properties (kept out of git). Without it, release builds are unsigned.
val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

// The relay for online games (see relay/): relay.url in gradle.properties. Debug builds can use
// another one, such as a relay running on this computer: relay.debugUrl in local.properties.
val relayUrl: String = providers.gradleProperty("relay.url").getOrElse("").trimEnd('/')
val debugRelayUrl: String = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}.getProperty("relay.debugUrl")?.trimEnd('/') ?: relayUrl

/** Points a build at [url]: the app talks to it, and its /join/ links open the app. */
fun com.android.build.api.dsl.VariantDimension.relay(url: String) {
    val uri = URI(url.ifBlank { "https://relay.invalid" })
    buildConfigField("String", "RELAY_URL", "\"$url\"")
    manifestPlaceholders["relayScheme"] = uri.scheme
    manifestPlaceholders["relayHost"] = uri.host
}

android {
    namespace = "com.andreaserick.multiversechess"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.andreaserick.multiversechess"
        minSdk = 24
        targetSdk = 37
        versionCode = 6
        versionName = "1.3.0"
        relay(relayUrl)
    }

    signingConfigs {
        if (!keystoreProperties.isEmpty) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            relay(debugRelayUrl)
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // The on-device model runtime's native libraries are large: keep them compressed in the APK.
        jniLibs.useLegacyPackaging = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":engine"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.okhttp)
    // Runs language models on the phone (arm64 and x86_64 only).
    implementation(libs.litertlm)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
