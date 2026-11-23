plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.scanpdf.app"
    compileSdk = 35

    defaultConfig {
        // TODO: đổi sang tên miền của công ty trước khi phát hành nội bộ.
        applicationId = "com.scanpdf.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // Điện thoại thật: 64-bit và 32-bit ARM.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        debug {
            // Thêm x86_64 để chạy được trên máy ảo Android Studio (OpenCV x86_64 nặng ~56 MB).
            ndk { abiFilters += "x86_64" }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
    }
    packaging {
        jniLibs {
            // Nén thư viện native trong APK: file APK nhỏ hơn nhiều, dễ gửi qua Zalo/email
            // (app nội bộ không phát hành qua Play Store nên không dùng App Bundle).
            useLegacyPackaging = true
        }
    }
}

// JVM unit tests run the real imaging code against the desktop OpenCV build (bundled Windows/Linux/macOS natives).
// The Android AAR must not be on that runtime classpath, otherwise two copies of org.opencv.* compete.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "org.opencv", module = "opencv")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.exifinterface)
    implementation(libs.opencv)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.opencv.desktop)
}
