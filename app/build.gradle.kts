plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.docscanner"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.docscanner"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Optional: package native code for one CPU type only, e.g. -PtargetAbi=arm64-v8a
        // for a much smaller APK to sideload onto a phone.
        providers.gradleProperty("targetAbi").orNull?.let { abi ->
            ndk { abiFilters += abi }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
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
    implementation(libs.onnxruntime.android)
    implementation(libs.mlkit.text.latin)
    implementation(libs.mlkit.text.devanagari)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
