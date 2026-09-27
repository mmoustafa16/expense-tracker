package expense.android.storage

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Prompts for biometric or device-credential auth. The session calls this once
 * per cold start, then keeps the unwrapped database key in process memory.
 */
class BiometricUnlockPrompt(
    private val activity: FragmentActivity,
    private val policy: KeystoreKeyPolicy = KeystoreKeyPolicy.DATABASE,
) : UnlockPrompt {
    override fun authenticate(onSuccess: () -> Unit, onFailure: () -> Unit) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onFailure()
                }
            },
        )
        prompt.authenticate(promptInfo(policy))
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
