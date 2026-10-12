package app.aaps.plugins.sync.garmin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.URI

class GarminQueryTest {

    @Test
    fun plusDecodedOnce() {
        // "+" reaches AAPS as %2B. Decoding twice would turn it into a space.
        assertEquals("a+b", URI("http://127.0.0.1/get?key=a%2Bb").queryParameter("key"))
    }

    @Test
    fun literalPlusKept() {
        // A client that does not encode the key: "+" must not become a space.
        assertEquals("a+b", URI("http://127.0.0.1/get?key=a+b").queryParameter("key"))
    }

    @Test
    fun percentDecodedOnce() {
        assertEquals("100%", URI("http://127.0.0.1/get?key=100%25").queryParameter("key"))
    }

    @Test
    fun equalsSignInValueKept() {
        assertEquals("b=c", URI("http://127.0.0.1/get?a=b=c").queryParameter("a"))
    }

    @Test
    fun otherParametersIgnored() {
        assertEquals("1200", URI("http://127.0.0.1/get?hr=60&steps=1200&test=true").queryParameter("steps"))
    }

    @Test
    fun missingParameter_Null() {
        assertNull(URI("http://127.0.0.1/get?steps=1200").queryParameter("hr"))
        assertNull(URI("http://127.0.0.1/get").queryParameter("hr"))
    }
}
