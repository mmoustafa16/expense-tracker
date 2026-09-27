import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

import privacy.NetworkClientPolicy
import privacy.SmsLogPolicy

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.sqldelight) apply false
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

    val scanSmsLogs = tasks.register("checkSmsLogPrivacy") {
        val sourceRoot = project.layout.projectDirectory.dir("src")
        inputs.dir(sourceRoot).optional()
        doLast {
            val root = sourceRoot.asFile
            if (!root.exists()) return@doLast
            val violations = root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    SmsLogPolicy.violations(file.readText(), file.relativeTo(project.projectDir).path).asSequence()
                }
                .toList()
            if (violations.isNotEmpty()) {
                throw GradleException(violations.joinToString(separator = "\n") { it.describe() })
            }
        }
    }
    tasks.withType<KotlinCompile>().configureEach {
        dependsOn(scanSmsLogs)
    }
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (NetworkClientPolicy.isForbidden(requested.group, requested.name)) {
                throw GradleException(
                    "Network client dependency ${requested.group}:${requested.name} is not allowed in ${project.path}.",
                )
            }
        }
    }
}
