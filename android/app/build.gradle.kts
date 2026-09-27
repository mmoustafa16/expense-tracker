plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "expense.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "expense.tracker"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":android:capture"))
    implementation(project(":android:storage"))
    implementation(project(":android:ui-unlock"))
    implementation(project(":android:ui-review"))
    implementation(project(":android:ui-ledger"))
    implementation(project(":android:ui-search"))
    implementation(project(":android:ui-analytics"))
    implementation(libs.activity.compose)
    // Biometric 1.1.0 pins Fragment 1.2.5, which rejects Activity Result
    // permission request codes (they are always >= 65536).
    implementation(libs.androidx.fragment)
    implementation(libs.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.sqldelight.sqlite.driver)
}
