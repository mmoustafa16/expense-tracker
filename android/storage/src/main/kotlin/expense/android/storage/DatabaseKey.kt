package expense.android.storage

import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher

sealed class KeyMaterial {
    class Available(val passphrase: ByteArray) : KeyMaterial() {
        override fun toString(): String = "Available"
    }

    data object Unavailable : KeyMaterial()

    data object AuthenticationRequired : KeyMaterial()
}

fun interface DatabaseKeyVault {
    fun readOrCreate(databaseExists: Boolean): KeyMaterial

    /**
     * Prepares unwrap or creation. A [KeyChallenge.NeedsCipher] cipher must be
     * authorized by the cold-start prompt before [KeyChallenge.NeedsCipher.finish].
     */
    fun challenge(databaseExists: Boolean): KeyChallenge = KeyChallenge.Deferred {
        readOrCreate(databaseExists)
    }
}

/**
 * Result of preparing a keystore wrap or unwrap.
 * [Deferred] runs only after the prompt succeeds.
 * [NeedsCipher] must be bound to that prompt first.
 */
sealed class KeyChallenge {
    class Deferred(val resolve: () -> KeyMaterial) : KeyChallenge()

    class NeedsCipher(
        val cipher: Cipher,
        val finish: (Cipher) -> KeyMaterial,
    ) : KeyChallenge()
}

/**
 * A software box completes [Done] immediately.
 * Android Keystore returns [Authorize] so the cipher can be bound to the prompt.
 */
sealed class BoxOperation {
    class Done(val bytes: ByteArray) : BoxOperation()

    class Authorize(
        val cipher: Cipher,
        val finish: (Cipher) -> ByteArray,
    ) : BoxOperation()
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

class KeyUnrecoverableException(cause: Throwable? = null) : Exception(cause)

class UserAuthRequiredException(cause: Throwable? = null) : Exception(cause)

interface SecretKeyBox {
    fun containsAlias(): Boolean

    fun deleteAlias()

    fun generate()

    fun encrypt(plaintext: ByteArray): ByteArray

    fun decrypt(wrapped: ByteArray): ByteArray

    /**
     * Null when this box does not bind a cipher to the biometric prompt.
     * The plaintext is encrypted only inside [BoxOperation.Authorize.finish].
     */
    fun openEncrypt(plaintext: ByteArray): BoxOperation? = null

    /** Null when unwrap does not need a prompt-bound cipher. */
    fun openDecrypt(wrapped: ByteArray): BoxOperation? = null
}

/**
 * API 30+ keys are per-use so [Cipher.init] succeeds before the prompt and
 * [javax.crypto.Cipher.doFinal] runs only on the cipher the prompt authorizes.
 * A positive timeout cannot be initialized until the user is already
 * authenticated, and a prompt without a [androidx.biometric.BiometricPrompt.CryptoObject]
 * does not authorize the key on Samsung devices.
 */
internal fun keystoreAuthenticationTimeoutSeconds(
    sdkInt: Int,
    policy: KeystoreKeyPolicy = KeystoreKeyPolicy.DATABASE,
): Int {
    return if (sdkInt >= 30) 0 else policy.authenticationValiditySeconds
}

/** API 30+ can bind a keystore cipher to biometric or device-credential auth. */
internal fun bindsKeystoreCipher(sdkInt: Int): Boolean = sdkInt >= 30

internal fun isUserAuthenticationFailure(error: Throwable): Boolean {
    val pending = ArrayDeque<Throwable>()
    pending.add(error)
    val seen = HashSet<Throwable>()
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        if (!seen.add(current)) continue
        if (current is UserAuthRequiredException) return true
        if (current.javaClass.name == "android.security.keystore.UserNotAuthenticatedException") return true
        val message = current.message?.lowercase().orEmpty()
        if (
            "user not authenticated" in message ||
            "user authentication required" in message ||
            "authentication required" in message
        ) {
            return true
        }
        if (current.javaClass.name.endsWith("KeyStoreException") && current.message?.trim() == "-26") {
            return true
        }
        current.cause?.let(pending::add)
    }
    return false
}

internal fun unlockedDeviceRequirementRejected(error: Throwable): Boolean {
    val pending = ArrayDeque<Throwable>()
    pending.add(error)
    val seen = HashSet<Throwable>()
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        if (!seen.add(current)) continue
        val message = current.message?.lowercase()?.trim().orEmpty()
        if ("unlocked" in message) return true
        if (message == "-66" || message == "-72" || message.endsWith(": -66") || message.endsWith(": -72")) {
            return true
        }
        current.cause?.let(pending::add)
    }
    return false
}

internal fun translateKeystoreFailure(error: Throwable): Exception {
    if (error is UserAuthRequiredException) return error
    if (error is KeyUnrecoverableException) return error
    if (error is java.util.concurrent.CancellationException) return error
    if (isUserAuthenticationFailure(error)) return UserAuthRequiredException(error)
    return KeyUnrecoverableException(error)
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

    override fun challenge(databaseExists: Boolean): KeyChallenge {
        return try {
            buildChallenge(databaseExists)
        } catch (_: UserAuthRequiredException) {
            KeyChallenge.Deferred { KeyMaterial.AuthenticationRequired }
        } catch (_: KeyUnrecoverableException) {
            KeyChallenge.Deferred { KeyMaterial.Unavailable }
        }
    }

    private fun buildChallenge(databaseExists: Boolean): KeyChallenge {
        val dbExists = databaseFile.exists() || databaseExists
        val present = box.containsAlias()
        val wrapPresent = wrapFile.isFile && wrapFile.length() > 0
        if (present && wrapPresent) {
            return when (val opened = box.openDecrypt(wrapFile.readBytes())) {
                is BoxOperation.Authorize -> KeyChallenge.NeedsCipher(opened.cipher) { cipher ->
                    try {
                        KeyMaterial.Available(opened.finish(cipher))
                    } catch (_: UserAuthRequiredException) {
                        KeyMaterial.AuthenticationRequired
                    } catch (_: KeyUnrecoverableException) {
                        KeyMaterial.Unavailable
                    }
                }
                is BoxOperation.Done -> KeyChallenge.Deferred { KeyMaterial.Available(opened.bytes) }
                null -> KeyChallenge.Deferred { readOrCreate(databaseExists) }
            }
        }
        if (dbExists) return KeyChallenge.Deferred { KeyMaterial.Unavailable }
        if (present) box.deleteAlias()
        val passphrase = random()
        return when (val opened = box.openEncrypt(passphrase)) {
            is BoxOperation.Authorize -> KeyChallenge.NeedsCipher(opened.cipher) { cipher ->
                if (databaseFile.exists()) {
                    KeyMaterial.Unavailable
                } else {
                    try {
                        writeAtomically(opened.finish(cipher))
                        KeyMaterial.Available(passphrase.copyOf())
                    } catch (_: UserAuthRequiredException) {
                        KeyMaterial.AuthenticationRequired
                    } catch (_: KeyUnrecoverableException) {
                        KeyMaterial.Unavailable
                    }
                }
            }
            is BoxOperation.Done -> {
                if (!databaseFile.exists()) writeAtomically(opened.bytes)
                KeyChallenge.Deferred { KeyMaterial.Available(passphrase.copyOf()) }
            }
            null -> KeyChallenge.Deferred { readOrCreate(databaseExists) }
        }
    }

    private fun createNew(replaceAlias: Boolean): KeyMaterial {
        if (databaseFile.exists()) return KeyMaterial.Unavailable
        return try {
            if (replaceAlias) box.deleteAlias()
            box.generate()
            val passphrase = random()
            writeAtomically(box.encrypt(passphrase))
            KeyMaterial.Available(passphrase)
        } catch (_: UserAuthRequiredException) {
            KeyMaterial.AuthenticationRequired
        } catch (_: KeyUnrecoverableException) {
            KeyMaterial.Unavailable
        }
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
