package io.fenjoon.app.billing

import androidx.activity.ComponentActivity

/** Direct installs pay through ZarinPal on the web, so there is no store billing. */
@Suppress("UNUSED_PARAMETER")
fun createStoreBilling(activity: ComponentActivity): StoreBilling? = null
