package io.github.manamiproject.modb.app.network

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.github.manamiproject.modb.app.TestAppConfig
import io.github.manamiproject.modb.app.TestConfigRegistry
import io.github.manamiproject.modb.app.config.Config
import io.github.manamiproject.modb.core.config.ConfigRegistry
import io.github.manamiproject.modb.core.httpclient.HttpClient
import io.github.manamiproject.modb.core.httpclient.HttpResponse
import io.github.manamiproject.modb.core.httpclient.RequestBody
import io.github.manamiproject.modb.core.httpclient.RetryCase
import io.github.manamiproject.modb.test.shouldNotBeInvoked
import java.net.URL
import io.github.manamiproject.modb.test.exceptionExpected
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.parallel.Isolated
import java.net.Proxy
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset.UTC
import kotlin.test.Test

@Isolated
internal class RotatingProxyNetworkControllerTest {

    @Nested
    inner class RotationTests {

        @Test
        fun `switches to the next proxy of the pool`() {
            runBlocking {
                // given
                val controller = RotatingProxyNetworkController(
                    appConfig = fixedClockConfig(),
                    configRegistry = poolOf("127.0.0.1:1111", "socks5://127.0.0.1:2222"),
                    cooldown = 0,
                )

                // when
                val first = controller.currentProxy()
                controller.restartAsync().await()
                val second = controller.currentProxy()
                controller.restartAsync().await()
                val third = controller.currentProxy()

                // then
                assertThat(first.type()).isEqualTo(Proxy.Type.HTTP)
                assertThat(first.address().toString()).endsWith(":1111")
                assertThat(second.type()).isEqualTo(Proxy.Type.SOCKS)
                assertThat(second.address().toString()).endsWith(":2222")
                assertThat(third).isEqualTo(first)
            }
        }

        @Test
        fun `network is active again once the switch completed`() {
            runBlocking {
                // given
                val controller = RotatingProxyNetworkController(
                    appConfig = fixedClockConfig(),
                    configRegistry = poolOf("127.0.0.1:1111", "127.0.0.1:2222"),
                    cooldown = 0,
                )

                // when
                controller.restartAsync().await()

                // then
                assertThat(controller.isNetworkActive()).isTrue()
            }
        }

        @Test
        fun `throws if no proxy has been configured`() {
            // given
            val controller = RotatingProxyNetworkController(
                appConfig = fixedClockConfig(),
                configRegistry = poolOf(),
            )

            // when
            val result = exceptionExpected<IllegalStateException> {
                controller.currentProxy()
            }

            // then
            assertThat(result).hasMessage("No proxies configured. Set [modb.app.network.proxies].")
        }

        @Test
        fun `throws if the pool is exhausted too often within the time range`() {
            // given
            val controller = RotatingProxyNetworkController(
                appConfig = fixedClockConfig(),
                configRegistry = poolOf("127.0.0.1:1111", "127.0.0.1:2222"),
                timeRangeForMaxRestarts = 600,
                maxNumberOfRestarts = 2,
                cooldown = 0,
            )

            // when
            val result = exceptionExpected<TooManyRestartsException> {
                repeat(5) { controller.restartAsync().await() }
            }

            // then
            assertThat(result).hasMessageContaining("2")
        }
    }

    @Nested
    inner class RouteTests {

        @Test
        fun `rotates through the given route instead of the configured pool`() {
            runBlocking {
                // given
                val controller = RotatingProxyNetworkController(
                    appConfig = fixedClockConfig(),
                    configRegistry = poolOf("127.0.0.1:1111"),
                    cooldown = 0,
                    route = listOf("127.0.0.1:3333"),
                )

                // when
                val result = controller.currentProxy()

                // then
                assertThat(result.address().toString()).endsWith(":3333")
            }
        }

        @Test
        fun `direct leaves without a proxy`() {
            runBlocking {
                // given
                val controller = RotatingProxyNetworkController(
                    appConfig = fixedClockConfig(),
                    configRegistry = poolOf(),
                    cooldown = 0,
                    route = listOf("direct", "127.0.0.1:1111"),
                )

                // when
                val first = controller.currentProxy()
                controller.restartAsync().await()
                val second = controller.currentProxy()

                // then
                assertThat(controller.hasProxies()).isTrue()
                assertThat(first).isEqualTo(Proxy.NO_PROXY)
                assertThat(second.address().toString()).endsWith(":1111")
            }
        }
    }

    @Nested
    inner class EgressTests {

        @Test
        fun `requests actually leave through the proxy which is currently active`() {
            val proxyOne = browserProxy("first-exit")
            val proxyTwo = browserProxy("second-exit")

            try {
                runBlocking {
                    // given
                    val controller = RotatingProxyNetworkController(
                        appConfig = fixedClockConfig(),
                        configRegistry = poolOf("127.0.0.1:${proxyOne.port()}", "127.0.0.1:${proxyTwo.port()}"),
                        cooldown = 0,
                    )
                    val httpClient = RotatingProxyHttpClient(networkController = controller)
                    val target = URI("http://anidb.net/anime/23").toURL()

                    // when
                    val before = httpClient.get(target).bodyAsString()
                    controller.restartAsync().await()
                    val after = httpClient.get(target).bodyAsString()
                    controller.restartAsync().await()
                    val wrappedAround = httpClient.get(target).bodyAsString()

                    // then
                    assertThat(before).isEqualTo("first-exit")
                    assertThat(after).isEqualTo("second-exit")
                    assertThat(wrappedAround).isEqualTo("first-exit")
                    proxyOne.verify(2, getRequestedFor(urlEqualTo("/anime/23")))
                    proxyTwo.verify(1, getRequestedFor(urlEqualTo("/anime/23")))
                }
            } finally {
                proxyOne.stop()
                proxyTwo.stop()
            }
        }

        @Test
        fun `composes with SuspendableHttpClient the way the crawlers wire it`() {
            val proxyOne = browserProxy("first-exit")
            val proxyTwo = browserProxy("second-exit")

            try {
                runBlocking {
                    // given
                    val controller = RotatingProxyNetworkController(
                        appConfig = fixedClockConfig(),
                        configRegistry = poolOf("127.0.0.1:${proxyOne.port()}", "127.0.0.1:${proxyTwo.port()}"),
                        cooldown = 0,
                    )
                    val httpClient = SuspendableHttpClient(
                        appConfig = fixedClockConfig(),
                        networkController = controller,
                        httpClient = RotatingProxyHttpClient(networkController = controller),
                    )
                    val target = URI("http://anidb.net/anime/23").toURL()

                    // when
                    val before = httpClient.get(target).bodyAsString()
                    controller.restartAsync().await()
                    val after = httpClient.get(target).bodyAsString()

                    // then
                    assertThat(before).isEqualTo("first-exit")
                    assertThat(after).isEqualTo("second-exit")
                }
            } finally {
                proxyOne.stop()
                proxyTwo.stop()
            }
        }

        @Test
        fun `reuses one delegate per proxy instead of creating a client per request`() {
            runBlocking {
                // given
                val created = mutableListOf<Proxy>()
                val controller = RotatingProxyNetworkController(
                    appConfig = fixedClockConfig(),
                    configRegistry = poolOf("127.0.0.1:1111", "127.0.0.1:2222"),
                    cooldown = 0,
                )
                val httpClient = RotatingProxyHttpClient(
                    networkController = controller,
                    httpClientFactory = { proxy ->
                        created.add(proxy)
                        object: HttpClient {
                            override suspend fun get(url: URL, headers: Map<String, Collection<String>>): HttpResponse {
                                return HttpResponse(200, proxy.address().toString())
                            }
                            override suspend fun post(url: URL, requestBody: RequestBody, headers: Map<String, Collection<String>>): HttpResponse = shouldNotBeInvoked()
                            override fun addRetryCases(vararg retryCases: RetryCase): HttpClient = this
                        }
                    },
                )
                val target = URI("http://anidb.net/anime/23").toURL()

                // when
                val responses = mutableListOf<String>()
                repeat(2) { responses.add(httpClient.get(target).bodyAsString()) }
                controller.restartAsync().await()
                repeat(2) { responses.add(httpClient.get(target).bodyAsString()) }
                controller.restartAsync().await()
                repeat(2) { responses.add(httpClient.get(target).bodyAsString()) }

                // then
                assertThat(created).hasSize(2)
                assertThat(responses.filter { it.endsWith("1111") }).hasSize(4)
                assertThat(responses.filter { it.endsWith("2222") }).hasSize(2)
            }
        }
    }

    private fun fixedClockConfig(): Config = object: Config by TestAppConfig {
        override fun clock(): Clock = Clock.fixed(Instant.parse("2026-08-26T20:00:00Z"), UTC)
    }

    private fun poolOf(vararg entries: String): ConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> list(key: String): List<T> = entries.toList() as List<T>
    }

    private fun browserProxy(body: String): WireMockServer {
        return WireMockServer(
            wireMockConfig()
                .dynamicPort()
                .enableBrowserProxying(true)
        ).apply {
            start()
            stubFor(get(urlEqualTo("/anime/23")).willReturn(aResponse().withStatus(200).withBody(body)))
        }
    }
}
