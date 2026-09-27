plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "expense.android.ui.review"
    compileSdk = 34

    defaultConfig {
        minSdk = 28
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
    api(project(":android:ui-common"))
    api(project(":android:storage"))
    api(project(":core:ingest"))
    api(project(":core:ledger"))
    api(project(":core:categories"))
    api(project(":core:money"))
    api(project(":core:parse"))
    api(project(":core:sms"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit.jupiter)
}
