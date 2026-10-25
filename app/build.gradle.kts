plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hesabak.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hesabak.app"
        minSdk = 21
        targetSdk = 34
        versionCode = 13
        versionName = "2.4"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")   // v7: النسخة التلقائية والتطبيق مغلق
    // v9: قارئ الباركود بالكاميرا (يعمل دون إنترنت). transitive=false + zxing core 3.3.0 ليبقى minSdk 21 صالحاً (تعليمات مكتبة zxing-android-embedded)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") { isTransitive = false }
    implementation("com.google.zxing:core:3.3.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
}
