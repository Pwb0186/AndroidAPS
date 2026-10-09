package app.aaps.plugins.sync.garmin

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.IntNonKey
import app.aaps.core.keys.StringNonKey
import app.aaps.plugins.sync.garmin.keys.GarminBooleanKey
import app.aaps.plugins.sync.garmin.keys.GarminIntKey
import app.aaps.plugins.sync.garmin.keys.GarminStringKey
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.atMost
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketAddress
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.locks.Condition
import kotlin.ranges.LongProgression.Companion.fromClosedRange

class GarminPluginTest : TestBaseWithProfile() {

    private lateinit var gp: GarminPlugin

    @Mock private lateinit var loopHub: LoopHub
    @Mock private lateinit var persistenceLayer: PersistenceLayer
    // Named garminSp so it can't clash with an "sp" in the test base class.
    @Mock private lateinit var garminSp: SP
    /** Backing store for [garminSp] - GarminV2Push keeps its app registry there. */
    private val spStore = mutableMapOf<String, Any>()
    private val clock = Clock.fixed(Instant.ofEpochMilli(10_000), ZoneId.of("UTC"))

    @BeforeEach
    fun setup() {
        whenever(garminSp.getString(any<String>(), any<String>())).thenAnswer { i ->
            spStore[i.getArgument(0)] as? String ?: i.getArgument<String>(1)
        }
        doAnswer { i -> spStore[i.getArgument(0)] = i.getArgument<String>(1); null }
            .whenever(garminSp).putString(any<String>(), any<String>())
        // rxBus and notificationManager: not used by the code under test.
        gp = GarminPlugin(aapsLogger, rh, preferences, garminSp, context, loopHub, persistenceLayer, mock())
        gp.clock = clock
        whenever(loopHub.currentProfileName).thenReturn("Default")
        whenever(preferences.get(GarminIntKey.LocalHttpPort)).thenReturn(28890)
        whenever(preferences.get(any<IntNonKey>())).thenAnswer { i -> 0 }
        whenever(preferences.get(any<BooleanNonKey>())).thenAnswer { i -> false }
        whenever(preferences.get(any<StringNonKey>())).thenAnswer { i -> "" }
        // Not covered by the StringNonKey stub above; the plugin never gets null in real use.
        whenever(preferences.get(GarminStringKey.RequestKey)).thenReturn("")
    }

    @AfterEach
    fun verifyNoFurtherInteractions() {
        verify(loopHub, atMost(2)).currentProfileName
        verify(loopHub, atMost(3)).insulinOnboard
        verify(loopHub, atMost(3)).insulinBasalOnboard
        verify(loopHub, atMost(3)).temporaryBasal
        verify(loopHub, atMost(3)).carbsOnboard
        verify(loopHub, atMost(3)).lowGlucoseMark
        verify(loopHub, atMost(3)).highGlucoseMark
        verify(loopHub, atMost(3)).temporaryTarget
        verify(loopHub, atMost(3)).isLoopEnabled
        verifyNoMoreInteractions(loopHub)
    }

    private val getGlucoseValuesFrom = clock.instant()
        .minus(2, ChronoUnit.HOURS)
        .minus(9, ChronoUnit.MINUTES)

    private fun createUri(params: Map<String, Any>): URI {
        return URI("http://foo?" + params.entries.joinToString(separator = "&") { (k, v) ->
            "$k=$v"
        })
    }

    private fun createHeartRate(@Suppress("SameParameterValue") heartRate: Int) = mapOf<String, Any>(
        "hr" to heartRate,
        "hrStart" to 1001L,
        "hrEnd" to 2001L,
        "device" to "Test_Device"
    )

    private fun createGlucoseValue(timestamp: Instant, value: Double = 93.0) = GV(
        id = 10 * timestamp.toEpochMilli(),
        timestamp = timestamp.toEpochMilli(), raw = 90.0, value = value,
        trendArrow = TrendArrow.FLAT, noise = 4.5,
        sourceSensor = SourceSensor.RANDOM
    )

    @Test
    fun testReceiveHeartRateMap() {
        val hr = createHeartRate(80)
        gp.receiveHeartRate(hr, false)
        verify(loopHub).storeHeartRate(
            Instant.ofEpochSecond(hr["hrStart"] as Long),
            Instant.ofEpochSecond(hr["hrEnd"] as Long),
            80,
            hr["device"] as String
        )
    }

    @Test
    fun testReceiveHeartRateUri() {
        val hr = createHeartRate(99)
        val uri = createUri(hr)
        gp.receiveHeartRate(uri)
        verify(loopHub).storeHeartRate(
            Instant.ofEpochSecond(hr["hrStart"] as Long),
            Instant.ofEpochSecond(hr["hrEnd"] as Long),
            99,
            hr["device"] as String
        )
    }

    @Test
    fun testReceiveHeartRate_UriTestIsTrue() {
        val params = createHeartRate(99).toMutableMap()
        params["test"] = true
        val uri = createUri(params)
        gp.receiveHeartRate(uri)
    }

    @Test
    fun testGetGlucoseValues_NoLast() {
        val from = getGlucoseValuesFrom
        val prev = createGlucoseValue(clock.instant().minusSeconds(310))
        whenever(loopHub.getGlucoseValues(from, true)).thenReturn(listOf(prev))
        assertArrayEquals(arrayOf(prev), gp.getGlucoseValues().toTypedArray())
        verify(loopHub).getGlucoseValues(from, true)
    }

    @Test
    fun testGetGlucoseValues_NoNewLast() {
        val from = getGlucoseValuesFrom
        val lastTimestamp = clock.instant()
        val prev = createGlucoseValue(clock.instant())
        gp.newValue = mock<Condition>()
        whenever(loopHub.getGlucoseValues(from, true)).thenReturn(listOf(prev))
        gp.onNewBloodGlucose(listOf(createGlucoseValue(lastTimestamp)))
        assertArrayEquals(arrayOf(prev), gp.getGlucoseValues().toTypedArray())

        verify(gp.newValue).signalAll()
        verify(loopHub).getGlucoseValues(from, true)
    }

    @Test
    // ": Unit": without it the last verify() made this return a value, and JUnit
    // silently skipped the test ("must not return a value").
    fun setupHttpServer_enabled(): Unit = runBlocking {
        whenever(preferences.get(GarminStringKey.RequestKey)).thenReturn("")
        whenever(preferences.get(GarminBooleanKey.LocalHttpServer)).thenReturn(true)
        whenever(preferences.get(GarminIntKey.LocalHttpPort)).thenReturn(28892)
        gp.setupHttpServer(Duration.ofSeconds(10))
        val reqUri = URI("http://127.0.0.1:28892/get")
        val resp = reqUri.toURL().openConnection() as HttpURLConnection
        assertEquals(200, resp.responseCode)

        // Change port
        whenever(preferences.get(GarminIntKey.LocalHttpPort)).thenReturn(28893)
        gp.setupHttpServer(Duration.ofSeconds(10))
        val reqUri2 = URI("http://127.0.0.1:28893/get")
        val resp2 = reqUri2.toURL().openConnection() as HttpURLConnection
        assertEquals(200, resp2.responseCode)

        whenever(preferences.get(GarminBooleanKey.LocalHttpServer)).thenReturn(false)
        gp.setupHttpServer(Duration.ofSeconds(10))
        assertThrows(ConnectException::class.java) {
            (reqUri2.toURL().openConnection() as HttpURLConnection).responseCode
        }
        gp.onStop()

        verify(loopHub, times(2)).getGlucoseValues(anyOrNull(), eq(true))
        verify(loopHub, times(2)).insulinOnboard
        verify(loopHub, times(2)).temporaryBasal
        verify(loopHub, times(2)).isConnected
        verify(loopHub, times(2)).glucoseUnit
        verify(loopHub, times(2)).lowGlucoseMark
        verify(loopHub, times(2)).highGlucoseMark
    }

    @Test
    fun setupHttpServer_disabled() {
        gp.setupHttpServer(Duration.ofSeconds(10))
        val reqUri = URI("http://127.0.0.1:28890/get")
        assertThrows(ConnectException::class.java) {
            (reqUri.toURL().openConnection() as HttpURLConnection).responseCode
        }
    }

    @Test
    fun requestHandler_NoKey() {
        whenever(preferences.get(GarminStringKey.RequestKey)).thenReturn("")
        val uri = createUri(emptyMap())
        val handler = gp.requestHandler { u: URI -> assertEquals(uri, u); "OK" }
        assertEquals(
            HttpURLConnection.HTTP_OK to "OK",
            handler(mock<SocketAddress>(), uri, null)
        )
    }

    @Test
    fun requestHandler_KeyProvided() {
        whenever(preferences.get(GarminStringKey.RequestKey)).thenReturn("")
        val uri = createUri(mapOf("key" to "foo"))
        val handler = gp.requestHandler { u: URI -> assertEquals(uri, u); "OK" }
        assertEquals(
            HttpURLConnection.HTTP_OK to "OK",
            handler(mock<SocketAddress>(), uri, null)
        )
    }

    @Test
    fun requestHandler_KeyRequiredAndProvided() {
        whenever(preferences.get(GarminStringKey.RequestKey)).thenReturn("foo")
        val uri = createUri(mapOf("key" to "foo"))
        val handler = gp.requestHandler { u: URI -> assertEquals(uri, u); "OK" }
        assertEquals(
            HttpURLConnection.HTTP_OK to "OK",
            handler(mock<SocketAddress>(), uri, null)
        )

    }

    @Test
    fun requestHandler_KeyRequired() {
        gp.garminMessengerField = mock<GarminMessenger>()

        whenever(preferences.get(GarminStringKey.RequestKey)).thenReturn("foo")
        val uri = createUri(emptyMap())
        val handler = gp.requestHandler { u: URI -> assertEquals(uri, u); "OK" }
        assertEquals(
            HttpURLConnection.HTTP_UNAUTHORIZED to "{}",
            handler(mock<SocketAddress>(), uri, null)
        )

        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(gp.garminMessenger).sendMessage(captor.capture() ?: "")
        @Suppress("UNCHECKED_CAST")
        val r = captor.value as Map<String, Any>
        assertEquals("foo", r["key"])
        assertEquals("glucose", r["command"])
        assertEquals("D", r["profile"])
        assertEquals("", r["encodedGlucose"])
        assertEquals(0.0, r["remainingInsulin"])
        assertEquals("mmoll", r["glucoseUnit"])
        assertEquals(0.0, r["temporaryBasalRate"])
        assertEquals(false, r["connected"])
        assertEquals(clock.instant().epochSecond, r["timestamp"])
        verify(loopHub).getGlucoseValues(getGlucoseValuesFrom, true)
        verify(loopHub).insulinOnboard
        verify(loopHub).temporaryBasal
        verify(loopHub).isConnected
        verify(loopHub).glucoseUnit
    }

    @Test
    fun onConnectDevice() {
        gp.garminMessengerField = mock<GarminMessenger>()
        whenever(preferences.get(GarminStringKey.RequestKey)).thenReturn("foo")
        val device = GarminDevice(mock(), 1, "Edge")
        gp.onConnectDevice(device)

        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(gp.garminMessenger).sendMessage(eq(device), captor.capture() ?: "")
        @Suppress("UNCHECKED_CAST")
        val r = captor.value as Map<String, Any>
        assertEquals("foo", r["key"])
        assertEquals("glucose", r["command"])
        assertEquals("D", r["profile"])
        assertEquals("", r["encodedGlucose"])
        assertEquals(0.0, r["remainingInsulin"])
        assertEquals("mmoll", r["glucoseUnit"])
        assertEquals(0.0, r["temporaryBasalRate"])
        assertEquals(false, r["connected"])
        assertEquals(clock.instant().epochSecond, r["timestamp"])
        verify(loopHub).getGlucoseValues(getGlucoseValuesFrom, true)
        verify(loopHub).insulinOnboard
        verify(loopHub).temporaryBasal
        verify(loopHub).isConnected
        verify(loopHub).glucoseUnit
    }

    @Test
    fun testOnGetBloodGlucose() {
        whenever(loopHub.isConnected).thenReturn(true)
        whenever(loopHub.insulinOnboard).thenReturn(3.14)
        whenever(loopHub.insulinBasalOnboard).thenReturn(2.71)
        whenever(loopHub.temporaryBasal).thenReturn(0.8)
        whenever(loopHub.lowGlucoseMark).thenReturn(70.0)
        whenever(loopHub.highGlucoseMark).thenReturn(130.0)
        val from = getGlucoseValuesFrom
        whenever(loopHub.getGlucoseValues(from, true)).thenReturn(
            listOf(createGlucoseValue(Instant.ofEpochSecond(1_000)))
        )
        val hr = createHeartRate(99)
        val uri = createUri(hr)
        val result = gp.onGetBloodGlucose(uri)
        assertEquals(
            """{"encodedGlucose":"0A+6AQ==",""" +
                """"remainingInsulin":3.14,"remainingBasalInsulin":2.71,"carbsOnBoard":0.0,""" +
                """"lowGlucoseMark":70,"highGlucoseMark":130,""" +
                """"glucoseUnit":"mmoll","temporaryBasalRate":0.8,""" +
                """"temporaryTargetActive":false,"connected":true,"loopEnabled":false,""" +
                """"timestamp":10,"profile":"D"}""",
            result.toString()
        )
        verify(loopHub).getGlucoseValues(from, true)
        verify(loopHub).insulinOnboard
        verify(loopHub).temporaryBasal
        verify(loopHub).isConnected
        verify(loopHub).glucoseUnit
        verify(loopHub).lowGlucoseMark
        verify(loopHub).highGlucoseMark
        verify(loopHub).storeHeartRate(
            Instant.ofEpochSecond(hr["hrStart"] as Long),
            Instant.ofEpochSecond(hr["hrEnd"] as Long),
            99,
            hr["device"] as String
        )
    }

    @Test
    fun testOnGetBloodGlucose_Wait() {
        whenever(loopHub.isConnected).thenReturn(true)
        whenever(loopHub.insulinOnboard).thenReturn(3.14)
        whenever(loopHub.temporaryBasal).thenReturn(0.8)
        whenever(loopHub.glucoseUnit).thenReturn(GlucoseUnit.MMOL)
        val from = getGlucoseValuesFrom
        whenever(loopHub.getGlucoseValues(from, true)).thenReturn(
            listOf(createGlucoseValue(clock.instant().minusSeconds(330)))
        )
        val params = createHeartRate(99).toMutableMap()
        params["wait"] = 10
        val uri = createUri(params)
        gp.newValue = mock<Condition>()
        val result = gp.onGetBloodGlucose(uri)
        assertEquals(
            """{"encodedGlucose":"/wS6AQ==",""" +
                """"remainingInsulin":3.14,"remainingBasalInsulin":0.0,"carbsOnBoard":0.0,""" +
                """"glucoseUnit":"mmoll","temporaryBasalRate":0.8,""" +
                """"temporaryTargetActive":false,"connected":true,"loopEnabled":false,""" +
                """"timestamp":10,"profile":"D"}""",
            result.toString()
        )
        verify(gp.newValue).awaitNanos(anyLong())
        verify(loopHub, times(2)).getGlucoseValues(from, true)
        verify(loopHub).insulinOnboard
        verify(loopHub).temporaryBasal
        verify(loopHub).isConnected
        verify(loopHub).glucoseUnit
        verify(loopHub).lowGlucoseMark
        verify(loopHub).highGlucoseMark
        verify(loopHub).storeHeartRate(
            Instant.ofEpochSecond(params["hrStart"] as Long),
            Instant.ofEpochSecond(params["hrEnd"] as Long),
            99,
            params["device"] as String
        )
    }

    @Test
    fun testOnPostCarbs() {
        val uri = createUri(mapOf("carbs" to "12"))
        assertEquals("", gp.onPostCarbs(uri))
        verify(loopHub).postCarbs(12)
    }

    @Test
    fun testOnConnectPump_Disconnect() {
        val uri = createUri(mapOf("disconnectMinutes" to "20"))
        whenever(loopHub.isConnected).thenReturn(false)
        assertEquals("{\"connected\":false}", gp.onConnectPump(uri))
        verify(loopHub).disconnectPump(20)
        verify(loopHub).isConnected
    }

    @Test
    fun testOnConnectPump_Connect() {
        val uri = createUri(mapOf("disconnectMinutes" to "0"))
        whenever(loopHub.isConnected).thenReturn(true)
        assertEquals("{\"connected\":true}", gp.onConnectPump(uri))
        verify(loopHub).connectPump()
        verify(loopHub).isConnected
    }

    @Test
    fun onSgv_NoGlucose() {
        whenever(loopHub.glucoseUnit).thenReturn(GlucoseUnit.MMOL)
        whenever(loopHub.getGlucoseValues(any(), eq(false))).thenReturn(emptyList())
        assertEquals("[]", gp.onSgv(createUri(mapOf())))
        verify(loopHub).getGlucoseValues(clock.instant().minusSeconds(25L * 300L), false)
    }

    @Test
    fun onSgv_NoDelta() {
        whenever(loopHub.glucoseUnit).thenReturn(GlucoseUnit.MMOL)
        whenever(loopHub.insulinOnboard).thenReturn(2.7)
        whenever(loopHub.insulinBasalOnboard).thenReturn(2.5)
        whenever(loopHub.temporaryBasal).thenReturn(0.8)
        whenever(loopHub.carbsOnboard).thenReturn(10.7)
        whenever(loopHub.getGlucoseValues(any(), eq(false))).thenReturn(
            listOf(
                createGlucoseValue(
                    clock.instant().minusSeconds(100L), 99.3
                )
            )
        )
        assertEquals(
            """[{"_id":"-900000","device":"RANDOM","deviceString":"1969-12-31T23:58:30Z","sysTime":"1969-12-31T23:58:30Z","unfiltered":90.0,"date":-90000,"sgv":99,"direction":"Flat","noise":4.5,"units_hint":"mmol","iob":5.2,"tbr":80,"cob":10.7}]""",
            gp.onSgv(createUri(mapOf()))
        )
        verify(loopHub).getGlucoseValues(clock.instant().minusSeconds(25L * 300L), false)
        verify(loopHub).glucoseUnit
    }

    @Test
    fun onSgv() {
        whenever(loopHub.glucoseUnit).thenReturn(GlucoseUnit.MMOL)
        whenever(loopHub.insulinOnboard).thenReturn(2.7)
        whenever(loopHub.insulinBasalOnboard).thenReturn(2.5)
        whenever(loopHub.temporaryBasal).thenReturn(0.8)
        whenever(loopHub.carbsOnboard).thenReturn(10.7)
        whenever(loopHub.getGlucoseValues(any(), eq(false))).thenAnswer { i ->
            val from = i.getArgument<Instant>(0)
            fromClosedRange(from.toEpochMilli(), clock.instant().toEpochMilli(), 300_000L)
                .map(Instant::ofEpochMilli)
                .mapIndexed { idx, ts -> createGlucoseValue(ts, 100.0 + (10 * idx)) }.reversed()
        }
        assertEquals(
            """[{"_id":"100000","device":"RANDOM","deviceString":"1970-01-01T00:00:10Z","sysTime":"1970-01-01T00:00:10Z","unfiltered":90.0,"date":10000,"sgv":120,"delta":10,"direction":"Flat","noise":4.5,"units_hint":"mmol","iob":5.2,"tbr":80,"cob":10.7}]""",
            gp.onSgv(createUri(mapOf("count" to "1")))
        )
        verify(loopHub).getGlucoseValues(
            clock.instant().minusSeconds(600L), false
        )


        assertEquals(
            """[{"_id":"100000","device":"RANDOM","deviceString":"1970-01-01T00:00:10Z","sysTime":"1970-01-01T00:00:10Z","unfiltered":90.0,"date":10000,"sgv":130,"delta":10,"direction":"Flat","noise":4.5,"units_hint":"mmol","iob":5.2,"tbr":80,"cob":10.7},""" +
                """{"_id":"-2900000","device":"RANDOM","deviceString":"1969-12-31T23:55:10Z","sysTime":"1969-12-31T23:55:10Z","unfiltered":90.0,"date":-290000,"sgv":120,"delta":10,"direction":"Flat","noise":4.5}]""",
            gp.onSgv(createUri(mapOf("count" to "2")))
        )
        verify(loopHub).getGlucoseValues(
            clock.instant().minusSeconds(900L), false
        )

        assertEquals(
            """[{"date":10000,"sgv":130,"delta":10,"direction":"Flat","noise":4.5,"units_hint":"mmol","iob":5.2,"tbr":80,"cob":10.7},""" +
                """{"date":-290000,"sgv":120,"delta":10,"direction":"Flat","noise":4.5}]""",
            gp.onSgv(createUri(mapOf("count" to "2", "brief_mode" to "true")))
        )
        verify(loopHub, times(2)).getGlucoseValues(
            clock.instant().minusSeconds(900L), false
        )

        verify(loopHub, atLeastOnce()).glucoseUnit
    }

    // ---- Push-to-pull (V2) -------------------------------------------------------

    private val appId = "0123456789ABCDEF0123456789ABCDEF"
    private fun v2Message(key: String = "") = mapOf<String, Any>("key" to key, "command" to "updateWatch")

    /** The loopHub reads of one onGetBloodGlucose() call that the tests don't check one by one. */
    private fun verifyGetBloodGlucoseReads() {
        verify(loopHub).getGlucoseValues(any(), eq(true))
        verify(loopHub).isConnected
        verify(loopHub).glucoseUnit
    }

    @Test
    fun requestHandler_KeyWithPlus() {
        // A "+" in the key reaches AAPS as %2B and must be decoded once. Decoding
        // uri.query (already decoded) a second time turned it into a space (401).
        whenever(preferences.get(GarminStringKey.RequestKey)).thenReturn("a+b")
        val uri = URI("http://foo?key=a%2Bb")
        val handler = gp.requestHandler { "OK" }
        assertEquals(HttpURLConnection.HTTP_OK to "OK", handler(mock<SocketAddress>(), uri, null))
    }

    @Test
    fun requestHandler_KeyWithPercent() {
        whenever(preferences.get(GarminStringKey.RequestKey)).thenReturn("100%")
        val uri = URI("http://foo?key=100%25")
        val handler = gp.requestHandler { "OK" }
        assertEquals(HttpURLConnection.HTTP_OK to "OK", handler(mock<SocketAddress>(), uri, null))
    }

    @Test
    fun onGetBloodGlucose_RegistersV2App() {
        whenever(loopHub.carbsOnboard).thenReturn(5.0)
        val result = gp.onGetBloodGlucose(createUri(mapOf("appId" to appId.lowercase())))
        assertEquals(setOf(appId), gp.garminV2Push.getActiveV2AppIds())
        Truth.assertThat(result.toString()).contains(""""carbsOnBoard":5.0""")
        // The plugin was never started (onStart), so the /get must not start a messenger.
        assertNull(gp.garminMessengerField)
        verifyGetBloodGlucoseReads()
    }

    @Test
    fun onGetBloodGlucose_MalformedAppIdIgnored() {
        whenever(loopHub.carbsOnboard).thenReturn(5.0)
        gp.onGetBloodGlucose(createUri(mapOf("appId" to "not-an-app-id")))
        assertEquals(emptySet<String>(), gp.garminV2Push.getActiveV2AppIds())
        verifyGetBloodGlucoseReads()
    }

    @Test
    fun onNewBloodGlucose_PushesToActiveApp() {
        val messenger = mock<GarminMessenger>()
        gp.garminMessengerField = messenger
        gp.garminV2Push.registerOrTouchDynamicApp(appId)
        gp.onNewBloodGlucose(listOf(createGlucoseValue(clock.instant())))
        verify(messenger).sendMessage(eq(v2Message()), eq(setOf(appId)))

        // Same BG again: no second push.
        gp.onNewBloodGlucose(listOf(createGlucoseValue(clock.instant())))
        verify(messenger, times(1)).sendMessage(eq(v2Message()), eq(setOf(appId)))
    }

    @Test
    fun onNewBloodGlucose_NoActiveApp() {
        val messenger = mock<GarminMessenger>()
        gp.garminMessengerField = messenger
        gp.onNewBloodGlucose(listOf(createGlucoseValue(clock.instant())))
        verifyNoInteractions(messenger)
    }

    @Test
    fun onNewBloodGlucose_PluginStopped_NoNewMessenger() {
        // A push that is still under way when the plugin stops must not start a new
        // messenger: onStop has disposed the old one, and nothing would dispose this one.
        // The plugin here was never started, which looks the same to the push.
        gp.garminV2Push.registerOrTouchDynamicApp(appId)
        gp.onNewBloodGlucose(listOf(createGlucoseValue(clock.instant())))
        assertNull(gp.garminMessengerField)
    }

    @Test
    fun onNewBloodGlucose_AppOutsidePushWindow() {
        val messenger = mock<GarminMessenger>()
        gp.garminMessengerField = messenger
        gp.garminV2Push.registerOrTouchDynamicApp(appId)
        // Last /get 16 min ago - the window is 15 min.
        gp.clock = Clock.offset(clock, Duration.ofMinutes(16))
        gp.onNewBloodGlucose(listOf(createGlucoseValue(gp.clock.instant())))
        verifyNoInteractions(messenger)
    }

    @Test
    fun onLoopDataChanged_PushesThrottled() {
        val messenger = mock<GarminMessenger>()
        gp.garminMessengerField = messenger
        gp.garminV2Push.registerOrTouchDynamicApp(appId)

        gp.onNewBloodGlucose(listOf(createGlucoseValue(clock.instant())))  // BG push
        gp.onLoopDataChanged()                             // < 3 s later: throttled
        verify(messenger, times(1)).sendMessage(eq(v2Message()), eq(setOf(appId)))

        gp.clock = Clock.offset(clock, Duration.ofSeconds(4))
        gp.onLoopDataChanged()                             // 4 s later: sent
        verify(messenger, times(2)).sendMessage(eq(v2Message()), eq(setOf(appId)))
    }

    @Test
    fun onGetBloodGlucose_CobMissing_FieldLeftOut() {
        // Without COB the field is left out, so the watch keeps its last value
        // instead of showing 0 g. Serving a push app does not push.
        val messenger = mock<GarminMessenger>()
        gp.garminMessengerField = messenger
        whenever(loopHub.carbsOnboard).thenReturn(null)

        val result = gp.onGetBloodGlucose(createUri(mapOf("appId" to appId)))
        Truth.assertThat(result.toString()).doesNotContain("carbsOnBoard")
        verifyNoInteractions(messenger)
        verify(loopHub).carbsOnboard
        verifyGetBloodGlucoseReads()
    }

    @Test
    fun maskKey() {
        assertEquals("/get?appId=A&key=***&trig=push", GarminPlugin.maskKey("/get?appId=A&key=000369&trig=push"))
        assertEquals("/get?key=***", GarminPlugin.maskKey("/get?key=a%2Bb"))
        // An empty key stays visible - it shows that no key is set.
        assertEquals("/get?appId=A&key=&trig=push", GarminPlugin.maskKey("/get?appId=A&key=&trig=push"))
        // Only the "key" parameter, not one that ends with "key".
        assertEquals("/get?appkey=x&key=***", GarminPlugin.maskKey("/get?appkey=x&key=y"))
    }

    @Test
    fun receiveHeartRate_NoHeartRateInRequest() {
        // A /get without hr/hrStart/hrEnd (most of them) stores nothing.
        gp.receiveHeartRate(createUri(mapOf("appId" to appId, "trig" to "push")))
        verify(loopHub, never()).storeHeartRate(any(), any(), any(), anyOrNull())
    }
}
