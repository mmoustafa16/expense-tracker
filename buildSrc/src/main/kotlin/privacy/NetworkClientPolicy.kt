package privacy

object NetworkClientPolicy {
    fun isForbidden(group: String?, name: String?): Boolean {
        val artifactGroup = group?.trim()?.lowercase().orEmpty()
        val artifactName = name?.trim()?.lowercase().orEmpty()
        if (artifactGroup.isEmpty() || artifactName.isEmpty()) return false
        if (artifactGroup == "app.cash.sqldelight") return false
        if (artifactGroup == "net.zetetic") return false
        if (artifactGroup == "androidx.sqlite" || artifactGroup.startsWith("androidx.sqlite.")) return false
        if (artifactGroup.startsWith("com.squareup.okhttp")) return true
        if (artifactGroup == "com.squareup.retrofit2") return true
        if (artifactGroup == "io.ktor" && artifactName.startsWith("ktor-client")) return true
        if (artifactGroup == "com.android.volley") return true
        if (artifactGroup.startsWith("com.github.kittinunf.fuel")) return true
        if (artifactGroup == "org.apache.httpcomponents" || artifactGroup.startsWith("org.apache.httpcomponents.")) {
            return true
        }
        if (artifactGroup == "com.google.http-client" || artifactGroup == "com.google.api-client") return true
        if (artifactGroup == "org.chromium.net" || artifactName.startsWith("cronet")) return true
        return artifactName == "okhttp" ||
            artifactName.startsWith("okhttp-") ||
            artifactName == "retrofit" ||
            artifactName.startsWith("retrofit-") ||
            artifactName == "volley" ||
            artifactName.startsWith("fuel-") ||
            artifactName == "ktor-client" ||
            artifactName.startsWith("ktor-client-")
    }
}
