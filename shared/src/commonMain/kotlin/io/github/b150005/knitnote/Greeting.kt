package io.github.b150005.knitnote

class Greeting {
    private val platform = getPlatform()

    fun greet(): String = "Hello from ${platform.name}!"
}
