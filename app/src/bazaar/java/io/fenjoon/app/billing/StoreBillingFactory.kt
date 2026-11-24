package io.fenjoon.app.billing

import androidx.activity.ComponentActivity

fun createStoreBilling(activity: ComponentActivity): StoreBilling? = PoolakeyStoreBilling(activity)
