package io.github.b150005.skeinly

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
