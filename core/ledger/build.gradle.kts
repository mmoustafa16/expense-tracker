plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core:money"))
    implementation(project(":core:sms"))
    api(project(":core:parse"))
    implementation(project(":core:merchants"))
    implementation(project(":core:categories"))
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
}
