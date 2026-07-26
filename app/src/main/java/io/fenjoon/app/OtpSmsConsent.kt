package io.fenjoon.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Parcelable
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status

private val FIVE_DIGIT_CODE = Regex("""(?<!\d)\d{5}(?!\d)""")

internal object OtpCodeParser {
    fun extract(message: String): String? {
        val normalized = buildString(message.length) {
            message.forEach { character ->
                append(
                    when (character) {
                        in '٠'..'٩' -> '0' + (character.code - '٠'.code)
                        in '۰'..'۹' -> '0' + (character.code - '۰'.code)
                        else -> character
                    }
                )
            }
        }
        return FIVE_DIGIT_CODE.find(normalized)?.value
    }
}

internal class OtpSmsConsentReceiver(
    private val onConsentIntent: (Intent) -> Unit
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SmsRetriever.SMS_RETRIEVED_ACTION) return

        val status = intent.parcelableExtra<Status>(SmsRetriever.EXTRA_STATUS) ?: return
        if (status.statusCode != CommonStatusCodes.SUCCESS) return

        intent.parcelableExtra<Intent>(SmsRetriever.EXTRA_CONSENT_INTENT)
            ?.let(onConsentIntent)
    }
}

internal inline fun <reified T : Parcelable> Intent.parcelableExtra(key: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }
