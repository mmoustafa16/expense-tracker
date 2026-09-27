plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:money"))
    implementation(project(":core:sms"))
    implementation(project(":core:parse"))
    implementation(project(":core:merchants"))
    implementation(project(":core:categories"))
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
}
