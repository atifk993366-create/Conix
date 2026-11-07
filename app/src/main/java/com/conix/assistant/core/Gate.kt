package com.conix.assistant.core

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Phone-unlock check (fingerprint / face / PIN) required before sensitive actions. */
object Gate {
    fun authenticate(activity: FragmentActivity, title: String, onResult: (Boolean) -> Unit) {
        val auth = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(activity).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) {
            onResult(false); return
        }
        val prompt = BiometricPrompt(
            activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onResult(true) }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { onResult(false) }
            }
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle("Confirm with your phone unlock")
                .setAllowedAuthenticators(auth)
                .build()
        )
    }
}
