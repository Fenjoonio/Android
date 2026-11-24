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
        versionCode = 24
        versionName = "0.2.3"

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

    // One build per distribution channel. STORE goes into the WebView user agent so the backend
    // can choose between ZarinPal (direct) and the store's in-app billing (bazaar, myket).
    flavorDimensions += "store"
    productFlavors {
        create("direct") {
            dimension = "store"
            buildConfigField("String", "STORE", "\"direct\"")
            buildConfigField("String", "IAB_PUBLIC_KEY", "\"\"")
        }
        create("bazaar") {
            dimension = "store"
            buildConfigField("String", "STORE", "\"bazaar\"")
            // Poolakey runs with local signature checks off; the backend verifies every purchase
            // token through Bazaar's developer API, which is what that mode requires.
            buildConfigField("String", "IAB_PUBLIC_KEY", "\"\"")
        }
        create("myket") {
            dimension = "store"
            buildConfigField("String", "STORE", "\"myket\"")
            // The Myket client verifies purchase signatures locally, so it needs the app's RSA
            // public key from the Myket developer panel: -PfenjoonMyketPublicKey=<base64 key>
            // (put it in ~/.gradle/gradle.properties, not in this repository).
            val myketPublicKey = (project.findProperty("fenjoonMyketPublicKey") as String?).orEmpty()
            buildConfigField("String", "IAB_PUBLIC_KEY", "\"$myketPublicKey\"")
            // Placeholders required by the Myket billing library's manifest.
            manifestPlaceholders["marketApplicationId"] = "ir.mservices.market"
            manifestPlaceholders["marketBindAddress"] = "ir.mservices.market.InAppBillingService.BIND"
            manifestPlaceholders["marketPermission"] = "ir.mservices.market.BILLING"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
    implementation(libs.google.auth.api.phone)
    "bazaarImplementation"(libs.poolakey)
    "myketImplementation"(libs.myket.billing.client)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling.debug)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}