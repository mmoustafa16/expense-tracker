package expense.android.storage

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class AndroidSecretKeyBox(
    private val alias: String = LedgerFiles.KEY_ALIAS,
    private val policy: KeystoreKeyPolicy = KeystoreKeyPolicy.DATABASE,
) : SecretKeyBox {
    override fun containsAlias(): Boolean {
        return try {
            store().containsAlias(alias)
        } catch (error: Exception) {
            throw translateKeystoreFailure(error)
        }
    }

    override fun deleteAlias() {
        try {
            store().deleteEntry(alias)
        } catch (error: Exception) {
            throw translateKeystoreFailure(error)
        }
    }

    override fun generate() {
        ensureGenerated()
    }

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        return try {
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val payload = cipher.doFinal(plaintext)
            cipher.iv + payload
        } catch (error: Exception) {
            throw translateKeystoreFailure(error)
        }
    }

    override fun decrypt(wrapped: ByteArray): ByteArray {
        if (wrapped.size <= IV_BYTES) throw KeyUnrecoverableException()
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = wrapped.copyOfRange(0, IV_BYTES)
            val payload = wrapped.copyOfRange(IV_BYTES, wrapped.size)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(payload)
        } catch (error: Exception) {
            throw translateKeystoreFailure(error)
        }
    }

    override fun openEncrypt(plaintext: ByteArray): BoxOperation {
        ensureGenerated()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        } catch (error: Exception) {
            throw translateKeystoreFailure(error)
        }
        val copy = plaintext.copyOf()
        return BoxOperation.Authorize(cipher) { authed ->
            try {
                val payload = authed.doFinal(copy)
                authed.iv + payload
            } catch (error: Exception) {
                throw translateKeystoreFailure(error)
            } finally {
                copy.fill(0)
            }
        }
    }

    override fun openDecrypt(wrapped: ByteArray): BoxOperation {
        if (wrapped.size <= IV_BYTES) throw KeyUnrecoverableException()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = wrapped.copyOfRange(0, IV_BYTES)
        val payload = wrapped.copyOfRange(IV_BYTES, wrapped.size)
        try {
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        } catch (error: Exception) {
            throw translateKeystoreFailure(error)
        }
        return BoxOperation.Authorize(cipher) { authed ->
            try {
                authed.doFinal(payload)
            } catch (error: Exception) {
                throw translateKeystoreFailure(error)
            }
        }
    }

    private fun ensureGenerated() {
        if (containsAlias()) return
        try {
            generateWith(policy)
        } catch (error: Exception) {
            if (isUserAuthenticationFailure(error)) throw UserAuthRequiredException(error)
            if (policy.unlockedDeviceRequired && unlockedDeviceRequirementRejected(error)) {
                runCatching { deleteAlias() }
                try {
                    generateWith(policy.copy(unlockedDeviceRequired = false))
                } catch (retry: Exception) {
                    throw translateKeystoreFailure(retry)
                }
                return
            }
            throw translateKeystoreFailure(error)
        }
    }

    private fun generateWith(active: KeystoreKeyPolicy) {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(spec(active))
        generator.generateKey()
    }

    private fun spec(active: KeystoreKeyPolicy): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(active.userAuthenticationRequired)
            .setInvalidatedByBiometricEnrollment(active.invalidatedByBiometricEnrollment)
            .setUnlockedDeviceRequired(active.unlockedDeviceRequired)
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setUserAuthenticationParameters(
                keystoreAuthenticationTimeoutSeconds(Build.VERSION.SDK_INT, active),
                authenticatorFlags(active),
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(active.authenticationValiditySeconds)
        }
        return builder.build()
    }

    private fun secretKey(): SecretKey {
        val key = try {
            store().getKey(alias, null) as? SecretKey
        } catch (error: UnrecoverableKeyException) {
            throw KeyUnrecoverableException(error)
        } catch (error: KeyPermanentlyInvalidatedException) {
            throw KeyUnrecoverableException(error)
        } catch (error: Exception) {
            throw translateKeystoreFailure(error)
        }
        return key ?: throw KeyUnrecoverableException()
    }

    private fun store(): KeyStore {
        return KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    }

    companion object {
        private const val ANDROID_KEY_STORE: String = "AndroidKeyStore"
        private const val TRANSFORMATION: String = "AES/GCM/NoPadding"
        private const val IV_BYTES: Int = 12
    }
}

internal fun authenticatorFlags(policy: KeystoreKeyPolicy): Int {
    var flags = 0
    if (policy.biometricAllowed) flags = flags or KeyProperties.AUTH_BIOMETRIC_STRONG
    if (policy.deviceCredentialAllowed) flags = flags or KeyProperties.AUTH_DEVICE_CREDENTIAL
    return flags
}
