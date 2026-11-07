package dev.zapstore.app

import dev.zapstore.app.screens.ActivityFilter
import dev.zapstore.app.transport.NetworkMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val state = DeviceState(networkMode = NetworkMode.DirectOnly)
        val json = state.toJson(JSONObject("""{"futureField": 1, "trustedSigners": ["$signer"]}"""))
        assertEquals("direct", json.getString("networkMode"))
        assertTrue(json.has("futureField"))
        assertEquals(signer, json.getJSONArray("trustedSigners").getString(0))
        assertEquals(state, DeviceState.fromJson(JSONObject(json.toString())))
    }

    @Test
    fun activityFilterRoundTripsAndDefaultsToReleases() {
        assertEquals(ActivityFilter.Releases, DeviceState.fromJson(JSONObject("""{"backgroundAutoUpdatesEnabled": true}""")).activityFilter)
        assertEquals(ActivityFilter.Releases, DeviceState.fromJson(JSONObject("""{"activityFilter": "nonsense"}""")).activityFilter)
        val social = DeviceState(activityFilter = ActivityFilter.All)
        assertEquals("all", social.toJson().getString("activityFilter"))
        assertEquals(social, DeviceState.fromJson(JSONObject(social.toJson().toString())))
    }

    @Test
    fun bugReportsDefaultOffAndRoundTrip() {
        assertFalse(DeviceState.fromJson(JSONObject("""{"backgroundAutoUpdatesEnabled": true}""")).bugReports)
        val on = DeviceState(bugReports = true)
        assertEquals(on, DeviceState.fromJson(JSONObject(on.toJson().toString())))
    }

    @Test
    fun oldTrustedSignersAreIgnored() {
        val json = JSONObject("""{"trustedSigners": ["short", "${"b".repeat(64)}"]}""")
        assertEquals(DeviceState(), DeviceState.fromJson(json))
    }
}
