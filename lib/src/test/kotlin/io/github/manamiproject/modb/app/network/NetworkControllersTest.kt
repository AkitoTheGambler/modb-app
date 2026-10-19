package io.github.manamiproject.modb.app.network

import io.github.manamiproject.modb.app.TestConfigRegistry
import io.github.manamiproject.modb.core.config.ConfigRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test

internal class NetworkControllersTest {

    @Nested
    inner class RouteForTests {

        @Test
        fun `returns null for a site without a route of its own`() {
            // given
            val registry = routes(listOf("anisearch.com = direct"), listOf("127.0.0.1:8901"))

            // when
            val result = NetworkControllers.routeFor("anidb.net", registry)

            // then
            assertThat(result).isNull()
        }

        @Test
        fun `returns null if no routes are configured`() {
            // given
            val registry = routes(null, listOf("127.0.0.1:8901"))

            // when
            val result = NetworkControllers.routeFor("anidb.net", registry)

            // then
            assertThat(result).isNull()
        }

        @Test
        fun `expands pool to the configured proxies in order`() {
            // given
            val registry = routes(
                listOf("anime-planet.com = http://172.18.0.27:1080, pool"),
                listOf("127.0.0.1:8901", "127.0.0.1:8902"),
            )

            // when
            val result = NetworkControllers.routeFor("anime-planet.com", registry)

            // then
            assertThat(result).containsExactly("http://172.18.0.27:1080", "127.0.0.1:8901", "127.0.0.1:8902")
        }

        @Test
        fun `matches the hostname regardless of case and spacing`() {
            // given
            val registry = routes(listOf("  AniSearch.com=direct"), emptyList())

            // when
            val result = NetworkControllers.routeFor("anisearch.com", registry)

            // then
            assertThat(result).containsExactly("direct")
        }

        @Test
        fun `rejects a route which names no entries`() {
            // given
            val registry = routes(listOf("anisearch.com = "), emptyList())

            // when
            val result = assertThrows<IllegalArgumentException> {
                NetworkControllers.routeFor("anisearch.com", registry)
            }

            // then
            assertThat(result).hasMessage("Route for [anisearch.com] names no entries.")
        }
    }

    private fun routes(routes: List<String>?, proxies: List<String>?): ConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> list(key: String): List<T>? = when (key) {
            "modb.app.network.routes" -> routes as List<T>?
            "modb.app.network.proxies" -> proxies as List<T>?
            else -> null
        }
    }
}
