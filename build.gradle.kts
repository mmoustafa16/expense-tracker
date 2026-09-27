import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}

subprojects {
    group = "expense.tracker"
    version = "0.1.0"

    // Keep the JDK 21 toolchain from Phase 1, but emit JVM 17 bytecode.
    // Kotlin 2.0.21 only supports AGP 8.5, and that compiler cannot read class-file version 65.
    listOf("org.jetbrains.kotlin.jvm", "org.jetbrains.kotlin.android").forEach { pluginId ->
        pluginManager.withPlugin(pluginId) {
            this@subprojects.tasks.withType<KotlinCompile>().configureEach {
                compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
            }
            this@subprojects.tasks.withType<JavaCompile>().configureEach {
                sourceCompatibility = JavaVersion.VERSION_17.toString()
                targetCompatibility = JavaVersion.VERSION_17.toString()
            }
        }
    }
}
