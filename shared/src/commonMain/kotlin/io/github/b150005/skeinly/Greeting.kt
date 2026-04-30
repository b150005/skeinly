package io.github.b150005.skeinly

class Greeting {
    private val platform = getPlatform()

    fun greet(): String = "Hello from ${platform.name}!"
}
