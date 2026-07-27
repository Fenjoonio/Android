package io.fenjoon.app

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import android.util.Log
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Arrays

/**
 * Computes the 11-character app hash required by the silent SMS Retriever API.
 *
 * Run an install once, copy the logged hash into the OTP SMS template (last line),
 * then this helper can stay debug-only. If you use Play App Signing, prefer a hash
 * from a Play-distributed build — local release signing can produce a different value.
 */
class AppSignatureHelper(context: Context) : ContextWrapper(context) {
    fun getAppSignatures(): List<String> {
        val packageName = packageName
        val signatures = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val packageInfo = packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                )
                val signingInfo = packageInfo.signingInfo ?: return emptyList()
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNATURES,
                ).signatures
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Unable to find package to obtain hash", e)
            return emptyList()
        } ?: return emptyList()

        return signatures.mapNotNull { signature ->
            hash(packageName, signature.toCharsString())
        }
    }

    private fun hash(packageName: String, signature: String): String? {
        val appInfo = "$packageName $signature"
        return try {
            val messageDigest = MessageDigest.getInstance(HASH_TYPE)
            messageDigest.update(appInfo.toByteArray(StandardCharsets.UTF_8))
            val hashSignature = Arrays.copyOfRange(messageDigest.digest(), 0, NUM_HASHED_BYTES)
            var base64Hash = Base64.encodeToString(
                hashSignature,
                Base64.NO_PADDING or Base64.NO_WRAP,
            )
            base64Hash = base64Hash.substring(0, NUM_BASE64_CHAR)
            Log.i(TAG, "SMS Retriever app hash: $base64Hash")
            base64Hash
        } catch (e: NoSuchAlgorithmException) {
            Log.e(TAG, "Hash algorithm not found", e)
            null
        }
    }

    companion object {
        private const val TAG = "AppSignatureHelper"
        private const val HASH_TYPE = "SHA-256"
        private const val NUM_HASHED_BYTES = 9
        private const val NUM_BASE64_CHAR = 11
    }
}
