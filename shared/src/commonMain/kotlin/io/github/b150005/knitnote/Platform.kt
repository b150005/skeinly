package io.github.b150005.knitnote

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
