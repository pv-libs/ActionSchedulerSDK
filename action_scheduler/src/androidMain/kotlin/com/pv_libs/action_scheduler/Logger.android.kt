package com.pv_libs.action_scheduler

import android.util.Log

actual val logger: (String) -> Unit = {
    Log.d("MyLogger", it)
}