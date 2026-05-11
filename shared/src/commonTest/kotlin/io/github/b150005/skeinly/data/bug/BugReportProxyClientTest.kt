package io.github.b150005.skeinly.data.bug

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 39 W5b (ADR-020) — Ktor MockEngine-backed tests for the
 * Edge Function client. Coverage matrix per the failure-mode
 * hierarchy declared in [BugReportProxyException].
 */
class BugReportProxyClientTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun submit_success_returns_SubmitOutcome() =
        runTest {
            val client =
                makeClient { request ->
                    assertEquals(
                        "https://skeinly.test.supabase.co/functions/v1/submit-bug-report",
                        request.url.toString(),
                    )
                    assertEquals("POST", request.method.value)
                    assertEquals(
                        "Bearer sb_publishable_test",
                        request.headers[HttpHeaders.Authorization],
                    )
                    respondJson(
                        """{"ok":true,"issue_number":42,"html_url":"https://github.com/b150005/skeinly/issues/42"}""",
                    )
                }
            val result = client.submit("[Beta] test", "body")
            assertTrue(result.isSuccess)
            val outcome = result.getOrThrow()
            assertEquals(42, outcome.issueNumber)
            assertEquals("https://github.com/b150005/skeinly/issues/42", outcome.htmlUrl)
        }

    @Test
    fun submit_with_empty_url_returns_ConfigMissing() =
        runTest {
            val client =
                BugReportProxyClient(
                    httpClient = HttpClient(MockEngine { error("should not be called") }),
                    supabaseUrl = "",
                    supabasePublishableKey = "sb_publishable_test",
                    json = json,
                )
            val result = client.submit("t", "b")
            assertIs<BugReportProxyException.ConfigMissing>(result.exceptionOrNull())
        }

    @Test
    fun submit_with_empty_key_returns_ConfigMissing() =
        runTest {
            val client =
                BugReportProxyClient(
                    httpClient = HttpClient(MockEngine { error("should not be called") }),
                    supabaseUrl = "https://x.supabase.co",
                    supabasePublishableKey = "",
                    json = json,
                )
            val result = client.submit("t", "b")
            assertIs<BugReportProxyException.ConfigMissing>(result.exceptionOrNull())
        }

    @Test
    fun submit_envelope_RATE_LIMITED_maps_to_RateLimited() =
        runTest {
            val client =
                makeClient {
                    respondJson("""{"ok":false,"code":"RATE_LIMITED","message":"try again in 47 minute(s)"}""")
                }
            val ex = client.submit("t", "b").exceptionOrNull()
            val rateLimited = assertIs<BugReportProxyException.RateLimited>(ex)
            assertTrue((rateLimited.message ?: "").contains("47"))
        }

    @Test
    fun submit_envelope_VALIDATION_FAILED_maps_to_ValidationFailed() =
        runTest {
            val client =
                makeClient {
                    respondJson("""{"ok":false,"code":"VALIDATION_FAILED","message":"title length must be 1..256"}""")
                }
            assertIs<BugReportProxyException.ValidationFailed>(
                client.submit("t", "b").exceptionOrNull(),
            )
        }

    @Test
    fun submit_envelope_CONFIG_MISSING_maps_to_ConfigMissing() =
        runTest {
            val client =
                makeClient {
                    respondJson("""{"ok":false,"code":"CONFIG_MISSING","message":"secrets absent"}""")
                }
            assertIs<BugReportProxyException.ConfigMissing>(
                client.submit("t", "b").exceptionOrNull(),
            )
        }

    @Test
    fun submit_envelope_GITHUB_AUTH_FAILED_maps_to_Server() =
        runTest {
            val client =
                makeClient {
                    respondJson("""{"ok":false,"code":"GITHUB_AUTH_FAILED","message":"Bad credentials"}""")
                }
            assertIs<BugReportProxyException.Server>(
                client.submit("t", "b").exceptionOrNull(),
            )
        }

    @Test
    fun submit_envelope_GITHUB_API_FAILED_maps_to_Server() =
        runTest {
            val client =
                makeClient {
                    respondJson("""{"ok":false,"code":"GITHUB_API_FAILED","message":"HTTP 503"}""")
                }
            assertIs<BugReportProxyException.Server>(
                client.submit("t", "b").exceptionOrNull(),
            )
        }

    @Test
    fun submit_envelope_unknown_code_maps_to_Unknown() =
        runTest {
            val client =
                makeClient {
                    respondJson("""{"ok":false,"code":"FUTURE_CODE","message":"unrecognised"}""")
                }
            val ex = client.submit("t", "b").exceptionOrNull()
            val unknown = assertIs<BugReportProxyException.Unknown>(ex)
            assertTrue((unknown.message ?: "").contains("FUTURE_CODE"))
        }

    @Test
    fun submit_http_500_maps_to_Server() =
        runTest {
            val client =
                makeClient {
                    respondError(HttpStatusCode.InternalServerError)
                }
            assertIs<BugReportProxyException.Server>(
                client.submit("t", "b").exceptionOrNull(),
            )
        }

    @Test
    fun submit_http_503_maps_to_Server() =
        runTest {
            val client =
                makeClient {
                    respondError(HttpStatusCode.ServiceUnavailable)
                }
            assertIs<BugReportProxyException.Server>(
                client.submit("t", "b").exceptionOrNull(),
            )
        }

    @Test
    fun submit_unparseable_json_maps_to_Unknown() =
        runTest {
            val client =
                makeClient {
                    respond(
                        ByteReadChannel("not json at all"),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            assertIs<BugReportProxyException.Unknown>(
                client.submit("t", "b").exceptionOrNull(),
            )
        }

    @Test
    fun submit_ok_envelope_with_missing_issue_number_maps_to_Unknown() =
        runTest {
            val client =
                makeClient {
                    respondJson("""{"ok":true,"html_url":"https://x"}""")
                }
            // ok:true but no issue_number — defensive Unknown classification
            // so the UI doesn't render a Success banner with #0 / null URL.
            val ex = client.submit("t", "b").exceptionOrNull()
            // The error maps to Unknown via mapErrorCode(null, null) because
            // ok+missing-fields falls into the failure branch.
            assertIs<BugReportProxyException.Unknown>(ex)
        }

    @Test
    fun submit_success_outcome_returns_no_exception() =
        runTest {
            val client =
                makeClient {
                    respondJson("""{"ok":true,"issue_number":1,"html_url":"u"}""")
                }
            val result = client.submit("t", "b")
            assertNull(result.exceptionOrNull())
        }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private fun makeClient(handler: io.ktor.client.engine.mock.MockRequestHandler): BugReportProxyClient {
        val httpClient = HttpClient(MockEngine(handler))
        return BugReportProxyClient(
            httpClient = httpClient,
            supabaseUrl = "https://skeinly.test.supabase.co",
            supabasePublishableKey = "sb_publishable_test",
            json = json,
        )
    }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = ByteReadChannel(body),
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )
}
