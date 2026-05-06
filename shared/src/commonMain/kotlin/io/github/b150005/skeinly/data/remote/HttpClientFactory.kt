package io.github.b150005.skeinly.data.remote

import io.ktor.client.HttpClient

/**
 * Phase 41.2b — platform-default Ktor [HttpClient] for non-Supabase HTTP
 * traffic (currently the symbol-pack signed-URL fetch).
 *
 * Why expect/actual: Ktor 3.x's `HttpClient { }` no-arg form picks the
 * engine via the JVM ServiceLoader on Android, but on Kotlin/Native the
 * Darwin engine is NOT discovered automatically and the no-arg form
 * throws at runtime. Each platform's actual constructs the client with
 * the explicit engine its build dependency carries
 * (`libs.ktor.client.android` / `libs.ktor.client.darwin`).
 */
expect fun createSymbolPackHttpClient(): HttpClient
