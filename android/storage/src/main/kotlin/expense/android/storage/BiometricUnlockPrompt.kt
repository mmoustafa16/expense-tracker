package expense.android.storage

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

/**
 * Prompts for biometric or device-credential auth once per cold start.
 * On API 30 and newer the SQLCipher wrap cipher is a [BiometricPrompt.CryptoObject],
 * so the fingerprint or device credential that succeeds is what authorizes the key.
 * The unwrapped passphrase then stays in process memory.
 */
class BiometricUnlockPrompt(
    private val activity: FragmentActivity,
    private val policy: KeystoreKeyPolicy = KeystoreKeyPolicy.DATABASE,
) : UnlockPrompt {
    override fun bindsCipher(): Boolean = bindsKeystoreCipher(android.os.Build.VERSION.SDK_INT)

    override fun authenticate(onSuccess: () -> Unit, onFailure: () -> Unit) {
        val prompt = biometricPrompt(
            onAuthenticated = { onSuccess() },
            onFailure = onFailure,
        )
        try {
            prompt.authenticate(promptInfo(policy))
        } catch (_: IllegalArgumentException) {
            onFailure()
        }
    }

    override fun authorize(cipher: Cipher, onSuccess: (Cipher) -> Unit, onFailure: () -> Unit) {
        val prompt = biometricPrompt(
            onAuthenticated = { result ->
                val authorized = result.cryptoObject?.cipher ?: cipher
                onSuccess(authorized)
            },
            onFailure = onFailure,
        )
        try {
            prompt.authenticate(promptInfo(policy), BiometricPrompt.CryptoObject(cipher))
        } catch (_: IllegalArgumentException) {
            onFailure()
        }
    }

    private fun biometricPrompt(
        onAuthenticated: (BiometricPrompt.AuthenticationResult) -> Unit,
        onFailure: () -> Unit,
    ): BiometricPrompt {
        val executor = ContextCompat.getMainExecutor(activity)
        return BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    try {
                        onAuthenticated(result)
                    } catch (_: Exception) {
                        onFailure()
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onFailure()
                }
            },
        )
    }

    companion object {
        internal fun promptInfo(policy: KeystoreKeyPolicy): BiometricPrompt.PromptInfo {
            val builder = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock expense ledger")
                .setSubtitle("Confirm it's you to open the ledger on this device")
            val authenticators = promptAuthenticators(policy)
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                builder.setAllowedAuthenticators(authenticators)
            } else if (policy.deviceCredentialAllowed) {
                @Suppress("DEPRECATION")
                builder.setDeviceCredentialAllowed(true)
            } else {
                builder.setAllowedAuthenticators(authenticators)
                builder.setNegativeButtonText("Cancel")
            }
            return builder.build()
        }

        internal fun promptAuthenticators(policy: KeystoreKeyPolicy): Int {
            var flags = 0
            if (policy.biometricAllowed) flags = flags or BiometricManager.Authenticators.BIOMETRIC_STRONG
            if (policy.deviceCredentialAllowed) flags = flags or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            return flags
        }
    }
}
