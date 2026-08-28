package com.emotiveautomaton.wristwork.health

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.health.services.client.HealthServices
import com.emotiveautomaton.wristwork.data.RawBatch
import com.emotiveautomaton.wristwork.data.TagDb
import com.emotiveautomaton.wristwork.work.DrainWorker
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What this watch can actually sense, asked of the device rather than of the documentation.
 *
 * Two questions, both answered at runtime: which Health Services data types this device supports
 * for BACKGROUND capture (the docs only guarantee heart rate and the daily aggregates; everything
 * else is per-device), and the full raw sensor list including vendor types — which is how skin
 * temperature was found in the first place.
 *
 * The answer is written into the health stream as one `kind: "inventory"` line, so it lands in the
 * append-only archive with a timestamp: a record of what the instrument could measure on that
 * date, which is exactly the sort of thing that is impossible to reconstruct later. Runs once per
 * app version, off the complication refresh, so it costs no wakeup of its own.
 */
object SensorInventory {

    private const val PREFS = "inventory"
    private const val KEY_VERSION = "posted_for_version"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun postOnce(context: Context) {
        val ctx = context.applicationContext
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
        }.getOrDefault(0L)
        if (prefs.getLong(KEY_VERSION, -1L) == version) return
        scope.launch {
            // Mark it done only once it is actually queued. Marking first meant a single silent
            // failure retired the probe forever, which is how the first attempt vanished
            // (2026-08-26) — and a failure that never retries is worse than a loud one.
            runCatching { collectAndQueue(ctx, version) }
                .onSuccess { prefs.edit().putLong(KEY_VERSION, version).apply() }
                .onFailure { android.util.Log.e("wristwork-health", "inventory failed", it) }
        }
    }

    private suspend fun collectAndQueue(ctx: Context, version: Long) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensors = JsonArray(
            sm.getSensorList(Sensor.TYPE_ALL).map { s ->
                JsonObject(mapOf(
                    "name" to JsonPrimitive(s.name),
                    "type" to JsonPrimitive(s.stringType ?: ""),
                    "vendor" to JsonPrimitive(s.vendor ?: ""),
                    // Wakeup sensors and reporting mode decide whether a channel can be read
                    // without holding the CPU awake — the difference between free and banned.
                    "wakeup" to JsonPrimitive(s.isWakeUpSensor),
                    "reporting" to JsonPrimitive(s.reportingMode),
                    "min_delay_us" to JsonPrimitive(s.minDelay),
                    "max_delay_us" to JsonPrimitive(s.maxDelay),
                    "fifo" to JsonPrimitive(s.fifoMaxEventCount),
                    "power_ma" to JsonPrimitive(s.power),
                ))
            }
        )

        val caps = runCatching {
            HealthServices.getClient(ctx).passiveMonitoringClient.getCapabilitiesAsync().get()
        }.getOrNull()
        fun names(set: Collection<*>?) =
            JsonArray((set ?: emptyList<Any>()).map { JsonPrimitive(it.toString()) })

        val payload = JsonObject(mapOf(
            "kind" to JsonPrimitive("inventory"),
            "app_version" to JsonPrimitive(version),
            "device" to JsonPrimitive("${android.os.Build.MODEL} / ${android.os.Build.DEVICE}"),
            "sdk" to JsonPrimitive(android.os.Build.VERSION.SDK_INT),
            "passive_data_types" to names(caps?.supportedDataTypesPassiveMonitoring),
            "passive_goal_types" to names(caps?.supportedDataTypesPassiveGoals),
            "health_event_types" to names(caps?.supportedHealthEventTypes),
            "user_activity_states" to names(caps?.supportedUserActivityStates),
            "sensors" to sensors,
        ))

        val db = TagDb.get(ctx)
        db.raw().insert(RawBatch(
            ts = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            payload = payload.toString(),
        ))
        DrainWorker.enqueue(ctx)
    }
}
