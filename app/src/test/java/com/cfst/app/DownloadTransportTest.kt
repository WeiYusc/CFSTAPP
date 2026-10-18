package com.cfst.app

import com.cfst.app.speedtest.*
import okhttp3.mockwebserver.*
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DownloadTransportTest {
    @Test fun sameHostRedirectIsNotFollowed() = runServer { server ->
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location",server.url("/second")))
        server.enqueue(MockResponse().setBody("must not read"))
        val result=DownloadTransport().measure("127.0.0.1",server.url("/first").toString(),server.port,500,ByteBudget(100),RunCancellation(),true)
        assertEquals(DownloadError.REDIRECT,result.error)
        assertEquals(0L,result.bytes)
        assertEquals(1,server.requestCount)
    }
    @Test fun systemProxyCannotRedirectCandidateDownload() = runServer { server ->
        MockWebServer().use { proxy ->
            proxy.start()
            val previous=java.net.ProxySelector.getDefault()
            java.net.ProxySelector.setDefault(object: java.net.ProxySelector() {
                override fun select(uri: java.net.URI)=listOf(java.net.Proxy(java.net.Proxy.Type.HTTP,java.net.InetSocketAddress("127.0.0.1",proxy.port)))
                override fun connectFailed(uri:java.net.URI,address:java.net.SocketAddress,error:java.io.IOException) {}
            })
            try {
                server.enqueue(MockResponse().setBody("x".repeat(100)))
                val result=DownloadTransport().measure("127.0.0.1",server.url("/").toString(),server.port,500,ByteBudget(100),RunCancellation(),true)
                assertEquals(100L,result.bytes)
                assertEquals("127.0.0.1",result.peer)
                assertEquals(0,proxy.requestCount)
            } finally { java.net.ProxySelector.setDefault(previous) }
        }
    }
    private fun runServer(block: (MockWebServer) -> Unit) { MockWebServer().use { it.start(); block(it) } }
    @Test fun selfSignedTlsIsRejectedWithoutTrustOverride() {
        val certificate = okhttp3.tls.HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(certificate).build()
        MockWebServer().use { server ->
            server.useHttps(tls.sslSocketFactory(), false); server.start()
            server.enqueue(MockResponse().setBody("secret"))
            val result = DownloadTransport().measure("127.0.0.1", server.url("/").toString(), server.port, 1000, ByteBudget(1000), RunCancellation())
            assertEquals(DownloadError.TLS, result.error)
            assertEquals(0L, result.bytes)
        }
    }
    @Test fun readsBoundedActualBytesAndVerifiesPeer() = runServer { server ->
        server.enqueue(MockResponse().setBody("x".repeat(100000)))
        val result = DownloadTransport().measure("127.0.0.1", server.url("/payload").toString(), server.port, 1000, ByteBudget(1234), RunCancellation(), true)
        assertEquals(1234L, result.bytes)
        assertEquals("127.0.0.1", result.peer)
        assertEquals(DownloadError.BUDGET, result.error)
        assertEquals("identity", server.takeRequest().getHeader("Accept-Encoding"))
    }
    @Test fun refusesCleartextByDefaultBeforeNetwork() = runServer { server ->
        val result = DownloadTransport().measure("127.0.0.1", server.url("/").toString(), server.port, 500, ByteBudget(1000), RunCancellation())
        assertEquals(DownloadError.CONFIG, result.error)
        assertEquals(0, server.requestCount)
    }
    @Test fun redirectsAreNeverFollowed() = runServer { server ->
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://example.com/"))
        val result = DownloadTransport().measure("127.0.0.1", server.url("/").toString(), server.port, 500, ByteBudget(1000), RunCancellation(), true)
        assertEquals(DownloadError.REDIRECT, result.error)
        assertEquals(1, server.requestCount)
    }
    @Test fun statusErrorsAreDistinct() = runServer { server ->
        server.enqueue(MockResponse().setResponseCode(403))
        val result = DownloadTransport().measure("127.0.0.1", server.url("/").toString(), server.port, 500, ByteBudget(1000), RunCancellation(), true)
        assertEquals(DownloadError.HTTP, result.error)
        assertEquals(403, result.status)
    }
    @Test fun stalledResponseHonorsTotalMonotonicDeadline() = runServer { server ->
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val start = System.nanoTime()
        val result = DownloadTransport().measure("127.0.0.1", server.url("/").toString(), server.port, 200, ByteBudget(1000), RunCancellation(), true)
        assertEquals(DownloadError.TIMEOUT, result.error)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500)
    }
    @Test fun stopCancelsBlockedCallPromptly() = runServer { server ->
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val token = RunCancellation()
        var result: DownloadMeasurement? = null
        val worker = Thread { result = DownloadTransport().measure("127.0.0.1", server.url("/").toString(), server.port, 10000, ByteBudget(1000), token, true) }
        worker.start()
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        token.cancel("user")
        worker.join(1500)
        assertFalse(worker.isAlive)
        assertEquals(DownloadError.CANCELLED, result!!.error)
    }
    @Test fun recordsNetworkSourceBytesIncludingBufferedPrefetch() = runServer { server ->
        server.enqueue(MockResponse().setBody("x".repeat(20000)).throttleBody(8192, 1, TimeUnit.SECONDS))
        val budget = ByteBudget(4000)
        val result = DownloadTransport().measure("127.0.0.1", server.url("/").toString(), server.port, 500, budget, RunCancellation(), true)
        assertEquals(4000L, budget.used)
        assertEquals(budget.used, result.bytes)
    }
    @Test fun compressedResponseCannotEvadePayloadBudget() = runServer { server ->
        server.enqueue(MockResponse().addHeader("Content-Encoding", "gzip").setBody("not-identity"))
        val result = DownloadTransport().measure("127.0.0.1", server.url("/").toString(), server.port, 500, ByteBudget(1000), RunCancellation(), true)
        assertEquals(DownloadError.HTTP, result.error)
        assertEquals(0L, result.bytes)
    }
    @Test fun sharedBudgetCountsEveryRequestNotDeclaredContentLength() = runServer { server ->
        repeat(3) { server.enqueue(MockResponse().setChunkedBody("x".repeat(300), 50)) }
        val budget = ByteBudget(700)
        val result = DownloadTransport().measure("127.0.0.1", server.url("/").toString(), server.port, 1000, budget, RunCancellation(), true)
        assertEquals(700L, result.bytes)
        assertEquals(700L, budget.used)
        assertEquals(3, server.requestCount)
    }
}
