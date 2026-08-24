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
import com.emotiveautomaton.wristwork.data.RawBatch
import com.emotiveautomaton.wristwork.data.TagDb
import com.emotiveautomaton.wristwork.work.DrainWorker
import java.time.Instant
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
 * Family-2 owned collection (HEALTH_DESIGN.md H1): MCU-batched passive heart rate delivered by
 * the platform, plus an opportunistic skin-temperature read piggybacked on each HR delivery
 * (no wakeups of our own). Batches queue in Room and drain to the `health` topic — the same
 * append-only discipline as labels. Collection starts now; the detector it feeds is H2-gated.
 */
class PassiveDataService : PassiveListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        val hr = dataPoints.getData(DataType.HEART_RATE_BPM)
        if (hr.isEmpty()) return
        val bootInstant = Instant.now().minusNanos(android.os.SystemClock.elapsedRealtimeNanos())
        val samples = hr.mapNotNull { dp ->
            runCatching {
                val t = bootInstant.plus(dp.timeDurationFromBoot).epochSecond
                JsonArray(listOf(JsonPrimitive(t), JsonPrimitive(dp.value)))
            }.getOrNull()
        }
        if (samples.isEmpty()) return
        val ctx = applicationContext
        scope.launch {
            val db = TagDb.get(ctx)
            db.raw().insert(RawBatch(
                ts = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                payload = JsonObject(mapOf(
                    "kind" to JsonPrimitive("hr"),
                    "samples" to JsonArray(samples),
                )).toString(),
            ))
            readSkinTempOnce(ctx)?.let { temp ->
                db.raw().insert(RawBatch(
                    ts = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    payload = JsonObject(mapOf(
                        "kind" to JsonPrimitive("skin_temp"),
                        "samples" to JsonArray(listOf(JsonArray(listOf(
                            JsonPrimitive(Instant.now().epochSecond), JsonPrimitive(temp))))),
                    )).toString(),
                ))
            }
            db.raw().prune(keepAfter = Long.MAX_VALUE)   // deletes uploaded rows; archive is the NAS
            DrainWorker.enqueue(ctx)
        }
    }

    /** One-shot skin temp read: register, first value, unregister. ~100 ms, no ongoing cost.
     *  Returns null when the sensor/permission is unavailable — collection degrades, never fails. */
    private fun readSkinTempOnce(ctx: Context): Float? {
        return runCatching {
            val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val sensor = sm.getSensorList(Sensor.TYPE_ALL)
                .firstOrNull { it.stringType == "com.google.sensor.skin_temperature" } ?: return null
            var value: Float? = null
            val latch = java.util.concurrent.CountDownLatch(1)
            val listener = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    value = e.values.firstOrNull(); latch.countDown()
                }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }
            if (!sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)) return null
            latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
            sm.unregisterListener(listener)
            value
        }.getOrNull()
    }

    companion object {
        /** Idempotent registration; called from the state complication's refresh path so it
         *  self-heals after reboots without any boot receiver. */
        fun ensureRegistered(context: Context) {
            runCatching {
                HealthServices.getClient(context).passiveMonitoringClient
                    .setPassiveListenerServiceAsync(
                        PassiveDataService::class.java,
                        PassiveListenerConfig.builder()
                            .setDataTypes(setOf(DataType.HEART_RATE_BPM))
                            .build(),
                    )
            }
        }
    }
}
