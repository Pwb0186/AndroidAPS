package app.aaps.plugins.sync.garmin

import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class GarminV2PushTest : TestBase() {

    private val store = mutableMapOf<String, String>()
    private val sp = mock<SP>()
    private var now = 1_000_000_000L

    private fun newPush() = GarminV2Push(aapsLogger, sp) { now }

    private fun appId(n: Int) = "%032X".format(n)

    @BeforeEach
    fun setup() {
        whenever(sp.getString(any<String>(), any<String>())).thenAnswer { i ->
            store[i.getArgument(0)] ?: i.getArgument<String>(1)
        }
        doAnswer { i -> store[i.getArgument(0)] = i.getArgument(1); null }
            .whenever(sp).putString(any<String>(), any<String>())
    }

    @Test
    fun matchesAppIdFormat() {
        val push = newPush()
        assertTrue(push.matchesAppIdFormat("0123456789ABCDEF0123456789ABCDEF"))
        assertTrue(push.matchesAppIdFormat("0123456789abcdef0123456789abcdef"))
        assertFalse(push.matchesAppIdFormat("0123456789ABCDEF0123456789ABCDE"))    // 31
        assertFalse(push.matchesAppIdFormat("0123456789ABCDEF0123456789ABCDEF0"))  // 33
        assertFalse(push.matchesAppIdFormat("0123456789-BCDEF0123456789ABCDEF"))
        assertFalse(push.matchesAppIdFormat(""))
    }

    @Test
    fun activeWindow() {
        val push = newPush()
        push.registerOrTouchDynamicApp(appId(1))
        assertEquals(setOf(appId(1)), push.getActiveV2AppIds())

        now += 14 * 60 * 1000L
        assertEquals(setOf(appId(1)), push.getActiveV2AppIds())

        now += 2 * 60 * 1000L  // 16 min after the last /get
        assertEquals(emptySet<String>(), push.getActiveV2AppIds())

        // A new /get makes it active again.
        push.registerOrTouchDynamicApp(appId(1))
        assertEquals(setOf(appId(1)), push.getActiveV2AppIds())
    }

    @Test
    fun registryPersistsAcrossInstances() {
        newPush().registerOrTouchDynamicApp(appId(1))
        // E.g. after an AAPS restart: the registry is read back from SP.
        assertEquals(setOf(appId(1)), newPush().getActiveV2AppIds())
    }

    @Test
    fun registryIsCapped() {
        val push = newPush()
        for (n in 1..12) {
            push.registerOrTouchDynamicApp(appId(n))
            now += 1_000L
        }
        val active = push.getActiveV2AppIds()
        assertEquals(10, active.size)
        // The two used least recently are dropped.
        assertFalse(active.contains(appId(1)))
        assertFalse(active.contains(appId(2)))
        assertTrue(active.contains(appId(12)))
    }

    @Test
    fun appsOutsidePushWindowRemoved() {
        val push = newPush()
        push.registerOrTouchDynamicApp(appId(1))
        now += 14 * 60 * 1000L
        push.registerOrTouchDynamicApp(appId(2))
        // 14 min: both are kept.
        Truth.assertThat(store.values.single()).contains(appId(1))

        now += 2 * 60 * 1000L  // 16 min after appId(1) polled
        push.registerOrTouchDynamicApp(appId(2))
        Truth.assertThat(store.values.single()).doesNotContain(appId(1))
        Truth.assertThat(store.values.single()).contains(appId(2))
    }

    @Test
    fun corruptRegistryIsIgnored() {
        store["garmin_dynamic_v2_apps"] = "not json"
        val push = newPush()
        assertEquals(emptySet<String>(), push.getActiveV2AppIds())
        push.registerOrTouchDynamicApp(appId(1))
        assertEquals(setOf(appId(1)), push.getActiveV2AppIds())
    }

    @Test
    fun glucoseMessageV2() {
        assertEquals(
            mapOf("key" to "k", "command" to "updateWatch"),
            newPush().getGlucoseMessageV2("k")
        )
    }
}
