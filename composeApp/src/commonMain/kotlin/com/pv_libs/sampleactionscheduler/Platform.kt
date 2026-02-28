package com.pv_libs.sampleactionscheduler

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform