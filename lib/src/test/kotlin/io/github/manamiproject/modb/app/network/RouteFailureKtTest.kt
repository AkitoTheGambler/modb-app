package io.github.manamiproject.modb.app.network

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test

internal class RouteFailureKtTest {

    @Nested
    inner class IsRouteFailureTests {

        @Test
        fun `a proxy refusing to open a tunnel is a route failure`() {
            // given
            val failure = IOException("Unexpected response code for CONNECT: 503")

            // when
            val result = failure.isRouteFailure()

            // then
            assertThat(result).isTrue()
        }

        @Test
        fun `a handshake answered with an untrusted certificate is a route failure`() {
            // given
            val failure = SSLHandshakeException("(certificate_unknown) PKIX path building failed")

            // when
            val result = failure.isRouteFailure()

            // then
            assertThat(result).isTrue()
        }

        @Test
        fun `a route failure is recognized when it is the cause of another exception`() {
            // given
            val failure = IllegalStateException("request failed", IOException("Unexpected response code for CONNECT: 502"))

            // when
            val result = failure.isRouteFailure()

            // then
            assertThat(result).isTrue()
        }

        @Test
        fun `any other io failure is not a route failure`() {
            // given
            val failure = SocketTimeoutException("timeout")

            // when
            val result = failure.isRouteFailure()

            // then
            assertThat(result).isFalse()
        }

        @Test
        fun `a failure without a message is not a route failure`() {
            // given
            val failure = IOException()

            // when
            val result = failure.isRouteFailure()

            // then
            assertThat(result).isFalse()
        }
    }
}
