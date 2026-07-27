plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

android {
    namespace = "io.fenjoon.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.fenjoon.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 23
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Origin of the Go API that serves the chat notification action endpoints
        // (/v1/conversations/<id>/{read,messages}). The WebView always loads the
        // production frontend at app.fenjoon.io, so the registered FCM tokens and
        // the payloads they receive come from the production backend — hence the
        // default. Override locally with -PfenjoonApiOrigin=... (e.g. for an
        // emulator pointing at a local backend: http://10.0.2.2:8080).
        val notificationApiOrigin = (project.findProperty("fenjoonApiOrigin") as String?)
            ?.takeIf(String::isNotBlank) ?: "https://api.fenjoon.io"
        buildConfigField("String", "NOTIFICATION_API_ORIGIN", "\"$notificationApiOrigin\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.material)
    implementation(libs.androidx.webkit)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.work.runtime)
    implementation(libs.play.services.auth.api.phone)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling.debug)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}