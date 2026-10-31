package app.aaps.plugins.sync.garmin

import app.aaps.shared.tests.TestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketAddress
import java.net.URI
import java.nio.charset.Charset
import java.time.Duration

internal class HttpServerTest: TestBase() {

    private fun toInputStream(s: String): InputStream {
        return ByteArrayInputStream(s.toByteArray(Charset.forName("ASCII")))
    }

    @Test fun testReadBody() {
        val input = toInputStream("Test")
        assertEquals("Test", HttpServer.readBody(input, 100))
    }

    @Test fun testReadBody_MoreContentThanLength() {
        val input = toInputStream("Test")
        assertEquals("Tes", HttpServer.readBody(input, 3))
    }

    @Test fun testParseRequest_Get() {
        val req = """ 
            GET http://foo HTTP/1.1
            """.trimIndent()
        assertEquals(
            URI("http://foo") to null,
            HttpServer.parseRequest(toInputStream(req)))
    }

    @Test fun testParseRequest_PostEmptyBody() {
        val req = """ 
            POST http://foo HTTP/1.1
            """.trimIndent()
        assertEquals(
            URI("http://foo") to null,
            HttpServer.parseRequest(toInputStream(req)))
    }

    @Test fun testParseRequest_PostBody() {
        val req = """ 
            POST http://foo HTTP/1.1
            Content-Type: application/x-www-form-urlencoded
            
            a=1&b=2
            """.trimIndent()
        assertEquals(
            URI("http://foo?a=1&b=2") to null,
            HttpServer.parseRequest(toInputStream(req)))
    }

    @Test fun testParseRequest_PostBodyContentLength() {
        val req = """ 
            POST http://foo HTTP/1.1
            Content-Type: application/x-www-form-urlencoded
            Content-Length: 3
            
            a=1&b=2
            """.trimIndent()
        assertEquals(
            URI("http://foo?a=1") to null,
            HttpServer.parseRequest(toInputStream(req)))
    }

    @Test fun testParseRequest_TooLong_Refused() {
        assertThrows(IOException::class.java) {
            HttpServer.parseRequest(toInputStream("GET http://foo?k=" + "a".repeat(9000) + " HTTP/1.1\n\n"))
        }
        val headers = (1..60).joinToString("\n") { "X-H: $it" }
        assertThrows(IOException::class.java) {
            HttpServer.parseRequest(toInputStream("GET http://foo HTTP/1.1\n$headers\n\n"))
        }
        assertThrows(IOException::class.java) {
            HttpServer.parseRequest(toInputStream("POST http://foo HTTP/1.1\nContent-Length: ${HttpServer.MAX_BODY + 1}\n\nx"))
        }
    }

    @Test fun testRequest() {
        // Bind an ephemeral port (0) and read the actual port back, so the test never collides with another
        // process or a previous run's port lingering in TIME_WAIT — the source of intermittent bind failures.
        HttpServer(aapsLogger, 0).use { server ->
            server.registerEndpoint("/foo")  { _: SocketAddress, uri: URI, _: String? ->
                    assertEquals(URI("/foo"), uri)
                    HttpURLConnection.HTTP_OK to "test"
                }
            assertTrue(server.awaitReady(Duration.ofSeconds(10)))
            val reqUri = URI("http://127.0.0.1:${server.localPort}/foo")
            val resp = reqUri.toURL().openConnection() as HttpURLConnection
            assertEquals(200, resp.responseCode)
            val content = (resp.content as InputStream).reader().use { r -> r.readText() }
            assertEquals("test", content)
        }
    }

    @Test fun testRequest_NotFound() {
        HttpServer(aapsLogger, 0).use { server ->
            assertTrue(server.awaitReady(Duration.ofSeconds(10)))
            val reqUri = URI("http://127.0.0.1:${server.localPort}/foo")
            val resp = reqUri.toURL().openConnection() as HttpURLConnection
            assertEquals(404, resp.responseCode)
        }
    }
}
