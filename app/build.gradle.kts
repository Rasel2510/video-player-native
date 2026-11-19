plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.ayesha.player"
    compileSdk = 37

    defaultConfig {
        // New id: installs side by side with the Flutter build during development (plan D1).
        applicationId = "com.ayesha.player"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // TODO: real signing config before release.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        // Compress native libraries in the APK: FFmpeg + libmpv shrink by about half. Android
        // unpacks them once at install (slightly slower install, a little more disk), in
        // exchange for a much smaller download.
        jniLibs { useLegacyPackaging = true }
        // Dozens of identical copies of the Apache license, one per AndroidX artifact.
        resources { excludes += listOf("/META-INF/androidx/**/LICENSE.txt", "/META-INF/*.version", "/META-INF/{AL2.0,LGPL2.1}") }
    }

    // The app is English-only: drop the translations bundled by AndroidX and Material.
    androidResources { localeFilters += listOf("en") }

    // libmpv ships ~25 MB of native code per ABI: one APK per ABI keeps each install small.
    // (An AAB for the Play Store splits by ABI automatically.) 32-bit x86 is emulator-only: dropped.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }
}

ksp {
    // Room schema history, so every future database migration can be tested against it.
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Hilt @ApplicationScope etc. on constructor params apply to param + property (Kotlin 2.x default-to-be).
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    // Installs the baseline profile (src/main/baseline-prof.txt) on sideloaded installs too.
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    // Vault: biometric unlock (BiometricPrompt needs a FragmentActivity).
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.coil.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Software decoder fallback (libmpv + FFmpeg), for codecs MediaCodec cannot decode.
    implementation(libs.libmpv)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.ui.compose)

    testImplementation(libs.junit)
}
