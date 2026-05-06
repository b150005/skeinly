package io.github.b150005.skeinly.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android

actual fun createSymbolPackHttpClient(): HttpClient = HttpClient(Android)
