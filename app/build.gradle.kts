import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "online.bmarian.bus"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "online.bmarian.bus"
        minSdk = 30
        targetSdk = 37
        // Phone and watch share the package, so each upload needs its own code: phone 1xxxx, watch 2xxxx,
        // then the release number, the same on both.
        versionCode = 20_007
        versionName = "1.2.1"

        buildConfigField("String", "API_BASE", "\"https://bus.bmarian.online/api/\"")
        buildConfigField("String", "SITE_ORIGIN", "\"https://bus.bmarian.online\"")
        // -Pabis=armeabi-v7a for a 32-bit-only APK.
        ndk {
            abiFilters += providers.gradleProperty("abis").orNull?.split(",")
                ?: listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("upload") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            (project.findProperty("apiBase") as String?)?.let { buildConfigField("String", "API_BASE", "\"$it\"") }
        }
        release {
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    useLibrary("wear-sdk")
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// The baseline profile plugin leaves R8 on for this variant.
androidComponents {
    finalizeDsl { android ->
        android.buildTypes.findByName("nonMinifiedRelease")?.optimization?.enable = false
    }
}

baselineProfile {
    dexLayoutOptimization = true
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.navigation)
    implementation(libs.compose.ui.tooling)
    implementation(libs.core.splashscreen)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.play.services.location)
    implementation(libs.play.services.wearable)
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.wear.tooling.preview)
    implementation(libs.zxing.core)
    implementation(libs.wear)
    implementation(libs.wear.ongoing)
    implementation(libs.wear.input)
    implementation(libs.wear.remote.interactions)
    implementation(libs.tiles)
    implementation(libs.protolayout)
    implementation(libs.protolayout.material3)
    implementation(libs.complications.data.source.ktx)
    implementation(libs.concurrent.futures.ktx)
    implementation(libs.material.icons.extended)
    implementation(libs.maplibre.android)
    implementation(libs.profileinstaller)
    baselineProfile(project(":baselineprofile"))
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.test.manifest)
    debugImplementation(libs.ui.tooling)
}
