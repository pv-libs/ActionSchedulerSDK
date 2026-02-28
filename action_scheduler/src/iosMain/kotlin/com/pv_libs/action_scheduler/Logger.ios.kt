package com.pv_libs.action_scheduler

actual val logger: (String) -> Unit
    get() = { println(it) }