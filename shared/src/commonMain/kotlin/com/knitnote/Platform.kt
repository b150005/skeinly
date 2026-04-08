package com.knitnote

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
