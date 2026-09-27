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
    override fun containsAlias(): Boolean = store().containsAlias(alias)

    override fun deleteAlias() {
        store().deleteEntry(alias)
    }

    override fun generate() {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(spec())
        generator.generateKey()
    }

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload = cipher.doFinal(plaintext)
        return cipher.iv + payload
    }

    override fun decrypt(wrapped: ByteArray): ByteArray {
        if (wrapped.size <= IV_BYTES) throw KeyUnrecoverableException()
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = wrapped.copyOfRange(0, IV_BYTES)
            val payload = wrapped.copyOfRange(IV_BYTES, wrapped.size)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(payload)
        } catch (_: KeyPermanentlyInvalidatedException) {
            throw KeyUnrecoverableException()
        } catch (_: UnrecoverableKeyException) {
            throw KeyUnrecoverableException()
        } catch (_: android.security.keystore.UserNotAuthenticatedException) {
            throw UserAuthRequiredException()
        }
    }

    private fun spec(): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(policy.userAuthenticationRequired)
            .setInvalidatedByBiometricEnrollment(policy.invalidatedByBiometricEnrollment)
            .setUnlockedDeviceRequired(policy.unlockedDeviceRequired)
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setUserAuthenticationParameters(
                policy.authenticationValiditySeconds,
                authenticatorFlags(policy),
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(policy.authenticationValiditySeconds)
        }
        return builder.build()
    }

    private fun secretKey(): SecretKey {
        val key = store().getKey(alias, null) as? SecretKey ?: throw KeyUnrecoverableException()
        return key
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
