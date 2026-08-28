package com.emotiveautomaton.wristwork.health

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.UserActivityInfo
import com.emotiveautomaton.wristwork.data.RawBatch
import com.emotiveautomaton.wristwork.data.TagDb
import com.emotiveautomaton.wristwork.work.DrainWorker
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Family-2 owned collection (HEALTH_DESIGN.md H1). The platform wakes us with batched passive
 * data; everything else here rides that wakeup and adds none of its own.
 *
 * Widened 2026-08-28 to every channel this device actually offers in the background — the device
 * was asked rather than the documentation (see [SensorInventory]): heart rate, and intraday
 * steps, calories, distance, floors and elevation gain, plus the platform's own activity state
 * (passive / exercise / asleep). On each delivery a short sweep reads the cheap on-change
 * sensors: skin temperature, off-body detection, ambient light, barometric pressure, cadence.
 *
 * Off-body detection matters more than it looks: a watch on a charger produces numbers that are
 * not about the wearer, and a detector that cannot tell the difference will happily learn from
 * them.
 *
 * Batches queue in Room and drain to the `health` topic under the same append-only discipline as
 * labels. Collection is H1; the detector that reads it is H2.
 */
class PassiveDataService : PassiveListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onUserActivityInfoReceived(info: UserActivityInfo) {
        val ctx = applicationContext
        val state = runCatching { info.userActivityState.name }.getOrNull() ?: return
        scope.launch {
            queue(ctx, JsonObject(mapOf(
                "kind" to JsonPrimitive("activity"),
                "state" to JsonPrimitive(state),
                "t" to JsonPrimitive(Instant.now().epochSecond),
            )))
        }
    }

    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        val bootInstant = Instant.now().minusNanos(android.os.SystemClock.elapsedRealtimeNanos())
        val ctx = applicationContext

        // Heart rate is the spine; the rest are covariates that make it interpretable.
        val hr = dataPoints.getData(DataType.HEART_RATE_BPM)
        val hrSamples = hr.mapNotNull { dp ->
            runCatching {
                val t = bootInstant.plus(dp.timeDurationFromBoot).epochSecond
                JsonArray(listOf(JsonPrimitive(t), JsonPrimitive(dp.value)))
            }.getOrNull()
        }

        val intervals = mutableMapOf<String, JsonArray>()
        fun <T : Number> collect(name: String, points: List<androidx.health.services.client.data.IntervalDataPoint<T>>) {
            if (points.isEmpty()) return
            val arr = points.mapNotNull { dp ->
                runCatching {
                    val t = bootInstant.plus(dp.endDurationFromBoot).epochSecond
                    JsonArray(listOf(JsonPrimitive(t), JsonPrimitive(dp.value)))
                }.getOrNull()
            }
            if (arr.isNotEmpty()) intervals[name] = JsonArray(arr)
        }
        runCatching { collect("steps", dataPoints.getData(DataType.STEPS)) }
        runCatching { collect("calories", dataPoints.getData(DataType.CALORIES)) }
        runCatching { collect("distance", dataPoints.getData(DataType.DISTANCE)) }
        runCatching { collect("floors", dataPoints.getData(DataType.FLOORS)) }
        runCatching { collect("elevation", dataPoints.getData(DataType.ELEVATION_GAIN)) }

        if (hrSamples.isEmpty() && intervals.isEmpty()) return

        scope.launch {
            if (hrSamples.isNotEmpty()) {
                queue(ctx, JsonObject(mapOf(
                    "kind" to JsonPrimitive("hr"),
                    "samples" to JsonArray(hrSamples),
                )))
            }
            for ((name, arr) in intervals) {
                queue(ctx, JsonObject(mapOf(
                    "kind" to JsonPrimitive(name),
                    "samples" to arr,
                )))
            }
            sweep(ctx)?.let { queue(ctx, it) }
            TagDb.get(ctx).raw().prune(keepAfter = Long.MAX_VALUE)  // drops uploaded rows; the NAS is the archive
            DrainWorker.enqueue(ctx)
        }
    }

    private suspend fun queue(ctx: Context, payload: JsonObject) {
        TagDb.get(ctx).raw().insert(RawBatch(
            ts = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            payload = payload.toString(),
        ))
    }

    /**
     * One short pass over the cheap on-change sensors. Registers, takes the first value of each,
     * unregisters — roughly a second in total, only on a wakeup the platform already paid for.
     * Anything unavailable is simply absent from the record rather than reported as zero.
     */
    private fun sweep(ctx: Context): JsonObject? = runCatching {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val wanted = mapOf(
            "skin_temp" to "com.google.sensor.skin_temperature",
            "offbody" to "android.sensor.low_latency_offbody_detect",
            "light" to "android.sensor.light",
            "pressure" to "android.sensor.pressure",
            "cadence" to "com.google.sensor.step_cadence",
        )
        val available = sm.getSensorList(Sensor.TYPE_ALL).associateBy { it.stringType }
        val values = mutableMapOf<String, Float>()
        val latch = CountDownLatch(wanted.count { available.containsKey(it.value) })
        val listeners = mutableListOf<Pair<SensorEventListener, Sensor>>()
        for ((name, stringType) in wanted) {
            val sensor = available[stringType] ?: continue
            val l = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    if (!values.containsKey(name)) {
                        e.values.firstOrNull()?.let { values[name] = it }
                        latch.countDown()
                    }
                }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }
            if (sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL)) {
                listeners += l to sensor
            } else latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        listeners.forEach { (l, _) -> sm.unregisterListener(l) }
        if (values.isEmpty()) return null
        JsonObject(
            mapOf(
                "kind" to JsonPrimitive("ctx"),
                "t" to JsonPrimitive(Instant.now().epochSecond),
            ) + values.mapValues { (_, v) -> JsonPrimitive(v) }
        )
    }.getOrNull()

    companion object {
        /** Idempotent registration; called from the state complication's refresh path so it
         *  self-heals after reboots without any boot receiver. */
        fun ensureRegistered(context: Context) {
            runCatching {
                val types = setOf(
                    DataType.HEART_RATE_BPM,
                    DataType.STEPS,
                    DataType.CALORIES,
                    DataType.DISTANCE,
                    DataType.FLOORS,
                    DataType.ELEVATION_GAIN,
                )
                val config = PassiveListenerConfig.builder()
                    .setDataTypes(types)
                    // Activity state needs ACTIVITY_RECOGNITION; without the grant the platform
                    // simply never sends it, and the rest of the stream is unaffected.
                    .setShouldUserActivityInfoBeRequested(true)
                    .build()
                val future = HealthServices.getClient(context).passiveMonitoringClient
                    .setPassiveListenerServiceAsync(PassiveDataService::class.java, config)
                future.addListener({
                    runCatching { future.get() }
                        .onSuccess { android.util.Log.i("wristwork-health", "passive registration OK") }
                        .onFailure { android.util.Log.e("wristwork-health", "passive registration FAILED", it) }
                }, { r -> r.run() })
            }.onFailure { android.util.Log.e("wristwork-health", "registration call threw", it) }
        }
    }
}
