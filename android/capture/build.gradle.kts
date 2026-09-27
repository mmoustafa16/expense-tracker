// Named `capture` rather than `sms`: an Android module named `sms` collides with
// `:core:sms` and makes the Android Gradle Plugin depend on its own compile jar.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "expense.android.sms"
    compileSdk = 34

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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

base {
    archivesName.set("android-capture")
}

dependencies {
    api(project(":core:ingest"))
    api(project(":core:sms"))
    api(project(":core:ledger"))
    api(project(":core:parse"))
    api(project(":core:money"))
    api(project(":core:merchants"))
    api(project(":core:categories"))
    testImplementation(libs.junit.jupiter)
}
