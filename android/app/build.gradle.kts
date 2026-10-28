plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.palminspire"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.garvinwong.claudeinspire"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        // 正式版：R8 压缩、不可调试（调试版 Compose 掉帧，实测）；先用调试密钥签名便于覆盖安装
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    // 零 Google 服务：网络 HttpURLConnection、JSON 系统 org.json、加密 javax.crypto
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.4")          // biometric 自带的 Fragment 太旧，ActivityResult 会用错请求码（lint 实报）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.commonmark:commonmark:0.22.0")                 // Markdown 解析（BSD-2）
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.22.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.22.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")                       // 单元测试里替代安卓桩实现
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
