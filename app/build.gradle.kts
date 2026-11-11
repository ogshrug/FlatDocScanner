import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.ogshrug.flatdocscanner"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.ogshrug.flatdocscanner"
        minSdk = 24
        targetSdk = 37
        versionCode = 4
        versionName = "1.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Optional: package native code for one CPU type only, e.g. -PtargetAbi=arm64-v8a
        // for a much smaller APK to sideload onto a phone.
        providers.gradleProperty("targetAbi").orNull?.let { abi ->
            ndk { abiFilters += abi }
        }
    }

    // Release signing comes from keystore.properties (git-ignored); see RELEASING.md.
    val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    // Where the app is distributed. "github" carries a self-updater and so the internet
    // permission; "play" has neither, because Play delivers updates and forbids apps
    // that update themselves.
    //
    // What runs the two neural networks. "onnx" uses ONNX Runtime; "ferrite" uses Ferrite
    // (third_party/ferrite, a git submodule), a small C runtime built here with the NDK. The
    // Ferrite build has its own package name, so both can be installed side by side.
    flavorDimensions += listOf("channel", "engine")
    productFlavors {
        create("github") {
            dimension = "channel"
            buildConfigField("String", "UPDATE_REPO", "\"ogshrug/FlatDocScanner\"")
        }
        create("play") {
            dimension = "channel"
        }
        create("onnx") {
            dimension = "engine"
            buildConfigField("String", "ENGINE", "\"onnx\"")
            // Nothing native of our own to build: the CMake project below skips itself.
            externalNativeBuild { cmake { arguments += "-DFERRITE_SKIP=ON" } }
        }
        create("ferrite") {
            dimension = "engine"
            applicationIdSuffix = ".ferrite"
            buildConfigField("String", "ENGINE", "\"ferrite\"")
        }
    }

    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake {
            path = file("src/ferrite/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    buildTypes {
        debug {
            // A separate package, so a debug build installs beside the release app
            // instead of being refused for its different signature.
            applicationIdSuffix = ".debug"
        }
        release {
            // R8: shrink and optimise the code; keep rules live in src/main/keepRules.
            optimization {
                enable = true
            }
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    androidResources {
        // The dewarping model is read straight from the APK.
        noCompress += "onnx"
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.opencv)
    "onnxImplementation"(libs.onnxruntime.android)
    implementation(libs.mlkit.text.latin)
    implementation(libs.mlkit.text.devanagari)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
