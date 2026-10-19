package dev.zapstore.app

import dev.zapstore.app.transport.NetworkMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceStateTest {
    @Test
    fun flutterContentKeepsDefaultsForMissingFields() {
        val flutter = JSONObject("""{"backgroundAutoUpdatesEnabled": true}""")
        val local = DeviceState(networkMode = NetworkMode.DirectOnly, backgroundAutoUpdatesAnyNetwork = true)
        assertEquals(
            DeviceState(backgroundAutoUpdatesAnyNetwork = true, networkMode = NetworkMode.DirectOnly),
            DeviceState.fromJson(flutter, local),
        )
    }

    @Test
    fun intervalWinsAndFlutterOffMeansNever() {
        val weekly = DeviceState(backgroundUpdateInterval = UpdateInterval.Weekly)
        assertEquals(UpdateInterval.Never, DeviceState.fromJson(JSONObject("""{"backgroundAutoUpdatesEnabled": false}"""), weekly).backgroundUpdateInterval)
        assertEquals(UpdateInterval.Weekly, DeviceState.fromJson(JSONObject("""{"backgroundAutoUpdatesEnabled": true}"""), weekly).backgroundUpdateInterval)
        val json = DeviceState(backgroundUpdateInterval = UpdateInterval.Every6Hours).toJson()
        assertEquals(true, json.getBoolean("backgroundAutoUpdatesEnabled"))
        assertEquals(UpdateInterval.Every6Hours, DeviceState.fromJson(json).backgroundUpdateInterval)
        assertEquals(false, DeviceState(backgroundUpdateInterval = UpdateInterval.Never).toJson().getBoolean("backgroundAutoUpdatesEnabled"))
    }

    @Test
    fun rewriteKeepsUnknownFields() {
        val signer = "a".repeat(64)
        val state = DeviceState(networkMode = NetworkMode.DirectOnly, trustedSigners = listOf(signer))
        val json = state.toJson(JSONObject("""{"futureField": 1}"""))
        assertEquals("direct", json.getString("networkMode"))
        assertTrue(json.has("futureField"))
        assertEquals(state, DeviceState.fromJson(JSONObject(json.toString())))
    }

    @Test
    fun bugReportsDefaultOnAndRoundTrip() {
        assertTrue(DeviceState.fromJson(JSONObject("""{"backgroundAutoUpdatesEnabled": true}""")).bugReports)
        val off = DeviceState(bugReports = false)
        assertEquals(off, DeviceState.fromJson(JSONObject(off.toJson().toString())))
    }

    @Test
    fun badSignersAreDropped() {
        val json = JSONObject("""{"trustedSigners": ["short", "${"b".repeat(64)}"]}""")
        assertEquals(listOf("b".repeat(64)), DeviceState.fromJson(json).trustedSigners)
    }
}
