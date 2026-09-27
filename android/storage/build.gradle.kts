plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.sqldelight)
}

android {
    namespace = "expense.android.storage"
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

sqldelight {
    databases {
        create("ExpenseDatabase") {
            packageName.set("expense.android.storage.db")
        }
    }
}

dependencies {
    implementation(project(":core:ingest"))
    implementation(project(":core:ledger"))
    implementation(project(":core:categories"))
    implementation(project(":core:money"))
    implementation(project(":core:sms"))
    implementation(project(":core:parse"))
    implementation(project(":core:merchants"))
    implementation(libs.sqldelight.android.driver)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.biometric)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.sqldelight.sqlite.driver)
}
