package expense.android.storage

import java.io.File
import java.security.SecureRandom

sealed class KeyMaterial {
    class Available(val passphrase: ByteArray) : KeyMaterial() {
        override fun toString(): String = "Available"
    }

    data object Unavailable : KeyMaterial()

    data object AuthenticationRequired : KeyMaterial()
}

fun interface DatabaseKeyVault {
    fun readOrCreate(databaseExists: Boolean): KeyMaterial
}

enum class KeyDecision {
    UNWRAP,
    CREATE,
    REFUSE,
}

fun keyDecision(
    databaseExists: Boolean,
    keystorePresent: Boolean,
    keystoreInvalid: Boolean,
    wrapPresent: Boolean,
): KeyDecision {
    if (keystorePresent && !keystoreInvalid && wrapPresent) return KeyDecision.UNWRAP
    if (databaseExists) return KeyDecision.REFUSE
    return KeyDecision.CREATE
}

class KeyUnrecoverableException : Exception()

class UserAuthRequiredException : Exception()

interface SecretKeyBox {
    fun containsAlias(): Boolean

    fun deleteAlias()

    fun generate()

    fun encrypt(plaintext: ByteArray): ByteArray

    fun decrypt(wrapped: ByteArray): ByteArray
}

/**
 * Wraps the SQLCipher passphrase. A missing or invalidated keystore key does not
 * delete or replace a database file that is already on disk.
 */
class WrappedDatabaseKey(
    private val databaseFile: File,
    private val wrapFile: File,
    private val box: SecretKeyBox,
    private val random: () -> ByteArray = {
        ByteArray(32).also { bytes -> SecureRandom().nextBytes(bytes) }
    },
) : DatabaseKeyVault {
    override fun readOrCreate(databaseExists: Boolean): KeyMaterial {
        val dbExists = databaseFile.exists() || databaseExists
        val present = box.containsAlias()
        val wrapPresent = wrapFile.isFile && wrapFile.length() > 0
        var invalid = false
        if (present && wrapPresent) {
            try {
                return KeyMaterial.Available(box.decrypt(wrapFile.readBytes()))
            } catch (_: UserAuthRequiredException) {
                return KeyMaterial.AuthenticationRequired
            } catch (_: KeyUnrecoverableException) {
                invalid = true
            }
        }
        return when (keyDecision(dbExists, present, invalid, wrapPresent)) {
            KeyDecision.UNWRAP -> KeyMaterial.Available(box.decrypt(wrapFile.readBytes()))
            KeyDecision.REFUSE -> KeyMaterial.Unavailable
            KeyDecision.CREATE -> createNew(replaceAlias = present)
        }
    }

    private fun createNew(replaceAlias: Boolean): KeyMaterial {
        if (databaseFile.exists()) return KeyMaterial.Unavailable
        if (replaceAlias) box.deleteAlias()
        box.generate()
        val passphrase = random()
        writeAtomically(box.encrypt(passphrase))
        return KeyMaterial.Available(passphrase)
    }

    private fun writeAtomically(bytes: ByteArray) {
        val parent = wrapFile.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val temporary = File(parent, wrapFile.name + ".tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(wrapFile)) {
            wrapFile.writeBytes(bytes)
            temporary.delete()
        }
    }
}

data class KeystoreKeyPolicy(
    val userAuthenticationRequired: Boolean,
    val invalidatedByBiometricEnrollment: Boolean,
    val authenticationValiditySeconds: Int,
    val biometricAllowed: Boolean,
    val deviceCredentialAllowed: Boolean,
    val unlockedDeviceRequired: Boolean,
) {
    init {
        require(userAuthenticationRequired)
        require(!invalidatedByBiometricEnrollment)
        require(authenticationValiditySeconds > 0)
        require(biometricAllowed || deviceCredentialAllowed)
    }

    companion object {
        val DATABASE: KeystoreKeyPolicy = KeystoreKeyPolicy(
            userAuthenticationRequired = true,
            invalidatedByBiometricEnrollment = false,
            authenticationValiditySeconds = 60,
            biometricAllowed = true,
            deviceCredentialAllowed = true,
            unlockedDeviceRequired = true,
        )
    }
}
