package expense.sms

import java.security.MessageDigest

object BodyHash {
    fun sha256(body: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            out.append("%02x".format(byte))
        }
        return out.toString()
    }
}
