import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The CARTO key stays out of the repository: put `carto.key=...` in local.properties.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.example.conexiuniclujwearos"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.conexiuniclujwearos"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "API_BASE", "\"https://bus.bmarian.online/api/\"")
        buildConfigField("String", "SITE_ORIGIN", "\"https://bus.bmarian.online\"")
        buildConfigField("String", "CARTO_KEY", "\"${localProperties.getProperty("carto.key", "")}\"")
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
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

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.navigation)
    implementation(libs.compose.ui.tooling)
    implementation(libs.core.splashscreen)
    implementation(libs.guava)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.play.services.location)
    implementation(libs.protolayout)
    implementation(libs.protolayout.material3)
    implementation(libs.tiles)
    implementation(libs.tiles.tooling.preview)
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.watchface.complications.data.source.ktx)
    implementation(libs.wear.tooling.preview)
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.tiles.renderer)
    debugImplementation(libs.tiles.tooling)
    debugImplementation(libs.ui.test.manifest)
    debugImplementation(libs.ui.tooling)
}
