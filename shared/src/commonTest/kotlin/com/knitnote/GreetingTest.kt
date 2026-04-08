package com.knitnote

import kotlin.test.Test
import kotlin.test.assertTrue

class GreetingTest {
    @Test
    fun greetingContainsHello() {
        val greeting = Greeting().greet()
        assertTrue(greeting.contains("Hello"), "Greeting should contain 'Hello'")
    }
}
