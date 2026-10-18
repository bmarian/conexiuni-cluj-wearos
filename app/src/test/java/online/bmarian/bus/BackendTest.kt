package online.bmarian.bus

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import online.bmarian.bus.data.Backend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.net.InetSocketAddress

class BackendTest {
    private fun request(path: String) = Request.Builder().url(Backend.base + path).build()

    @Test
    fun theOldServerIsAskedAsBefore() {
        val request = request("network/version")
        assertSame(request, Backend.route(request, "network/version", perCity = false))
    }

    @Test
    fun theNewServerIsAskedUnderTheCity() {
        for (path in listOf("network/version", "live?stops=1&once=1", "shapes?format=polyline&shape_ids=1")) {
            assertEquals(Backend.base + "cluj/" + path, Backend.route(request(path), path, perCity = true).url.toString())
        }
    }

    @Test
    fun siteWidePathsStayWhereTheyAre() {
        for (path in listOf("transfer", "transfer/abc", "cities")) {
            val request = request(path)
            assertSame(request, Backend.route(request, path, perCity = true))
        }
    }

    @Test
    fun onlyTheNewServerListsCities() {
        assertEquals(true, Backend.decide(200, "[{\"slug\":\"cluj\"}]"))
        assertEquals(false, Backend.decide(404, "Not Found"))
        assertNull(Backend.decide(502, "<html>"))
        assertNull(Backend.decide(200, "<html>"))
    }

    // One server that is the old one, then the new one, then the old one again, as a rollback would make it.
    @Test
    fun followsTheServerOfTheDay() {
        var perCity = false
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val (code, body) = when {
                perCity && path == "/api/cities" -> 200 to "[{\"slug\":\"cluj\"}]"
                perCity && path == "/api/cluj/network/version" -> 200 to "new"
                perCity && path == "/api/transfer/abc" -> 200 to "shared"
                !perCity && path == "/api/network/version" -> 200 to "old"
                else -> 404 to "Not Found"
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val base = Backend.base
        Backend.base = "http://127.0.0.1:${server.address.port}/api/"
        val client = OkHttpClient.Builder().addInterceptor(Backend).build()
        fun get(path: String) = client.newCall(Request.Builder().url(Backend.base + path).build()).execute().use { it.body?.string() }
        try {
            assertEquals("old", get("network/version"))
            perCity = true
            assertEquals("new", get("network/version"))
            assertEquals("shared", get("transfer/abc"))
            perCity = false
            assertEquals("old", get("network/version"))
        } finally {
            Backend.base = base
            server.stop(0)
        }
    }
}
