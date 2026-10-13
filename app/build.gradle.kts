plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("kotlin-kapt")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.dagger.hilt.android")
}

// Override with -Ppresence.backendUrl=... or in ~/.gradle/gradle.properties.
// 10.0.2.2 is the host machine as seen from the Android emulator.
val backendUrl = providers.gradleProperty("presence.backendUrl").getOrElse("http://10.0.2.2:8787")
// Privy app (dashboard.privy.io): public IDs, not secrets. Google login + embedded wallet on Monad.
val privyAppId = providers.gradleProperty("presence.privyAppId").getOrElse("")
val privyClientId = providers.gradleProperty("presence.privyClientId").getOrElse("")
val network = providers.gradleProperty("presence.network").getOrElse("devnet")
require(network in setOf("localnet", "devnet", "mainnet")) { "presence.network must be localnet|devnet|mainnet" }

android {
    namespace = "com.presence"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.presence"
        minSdk = 28  // Privy embedded wallets need API 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "BACKEND_URL", "\"$backendUrl\"")
        buildConfigField("String", "NETWORK", "\"$network\"")
        buildConfigField("String", "PRIVY_APP_ID", "\"$privyAppId\"")
        buildConfigField("String", "PRIVY_CLIENT_ID", "\"$privyClientId\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Release signing comes from -Ppresence.releaseKeystore/-Alias/-Password (kept outside the repo).
    val releaseKeystore = providers.gradleProperty("presence.releaseKeystore").orNull
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = file(releaseKeystore)
            storePassword = providers.gradleProperty("presence.releasePassword").get()
            keyAlias = providers.gradleProperty("presence.releaseKeyAlias").get()
            keyPassword = storePassword
        }
    }

    buildTypes {
        release {
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/previous-compilation-data.bin"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("io.ktor:ktor-client-core:3.0.3")
    implementation("io.ktor:ktor-client-android:3.0.3")

    implementation("io.github.funkatronics:multimult:0.2.0")
    implementation("io.privy:privy-core:0.16.0")
    implementation("com.google.zxing:core:3.5.3") // QR code of the on-chain proof on the receipt
    implementation("com.solanamobile:web3-solana:0.2.2")
    implementation("com.solanamobile:mobile-wallet-adapter-clientlib-ktx:2.0.0")

    implementation("com.google.dagger:hilt-android:2.55")
    kapt("com.google.dagger:hilt-compiler:2.55")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kapt {
    correctErrorTypes = true
}
