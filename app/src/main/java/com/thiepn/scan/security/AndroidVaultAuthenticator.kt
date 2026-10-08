package com.thiepn.scan.security

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

internal fun requestVaultAuthentication(
    activity: FragmentActivity,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    val biometric = BiometricManager.from(activity)
    val keyguard = activity.getSystemService(
        Context.KEYGUARD_SERVICE
    ) as KeyguardManager
    val executor = ContextCompat.getMainExecutor(activity)

    val canUseBiometric = biometric.canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_WEAK
    ) == BiometricManager.BIOMETRIC_SUCCESS
    val canUseCredential = keyguard.isDeviceSecure
    if (!canUseBiometric && !canUseCredential) {
        onError(
            "Set up biometrics or a device screen lock before using the secure vault."
        )
        return
    }

    val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(
                result: BiometricPrompt.AuthenticationResult
            ) {
                onSuccess()
            }

            override fun onAuthenticationError(
                errorCode: Int,
                errString: CharSequence
            ) {
                onError(errString.toString())
            }
        }
    )

    val builder = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Unlock secure document")
        .setSubtitle(
            "Authenticate to decrypt this document."
        )

    if (Build.VERSION.SDK_INT >= 30) {
        builder.setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
    } else {
        @Suppress("DEPRECATION")
        builder.setDeviceCredentialAllowed(true)
    }

    prompt.authenticate(builder.build())
}
