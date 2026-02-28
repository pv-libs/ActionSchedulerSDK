package com.pv_libs.sampleactionscheduler

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "SampleActionScheduler",
    ) {
        App()
    }
}