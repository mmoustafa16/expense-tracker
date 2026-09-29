plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core:parse"))
    implementation(project(":core:money"))
    implementation(libs.org.json)
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
}
