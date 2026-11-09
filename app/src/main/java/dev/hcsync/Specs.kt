package dev.hcsync

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.reflect.KClass

/**
 * One syncable Health Connect record type and how it becomes JSON.
 * The JSON shapes match what the health-connect-mcp `/ingest` endpoint stores: every record carries
 * `metadata.id` (Health Connect's record id, so re-syncs overwrite instead of duplicating),
 * `metadata.data_origin` (the app that wrote it) and `metadata.last_modified_time`.
 */
class Spec<T : Record>(val name: String, val cls: KClass<T>, private val map: (T) -> JSONObject?) {
    @Suppress("UNCHECKED_CAST")
    fun convert(r: Record): JSONObject? = map(r as T)
}

private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

fun iso(i: Instant, off: ZoneOffset?): String =
    i.atOffset(off ?: ZoneId.systemDefault().rules.getOffset(i)).format(ISO)

private fun meta(m: Metadata): JSONObject = JSONObject()
    .put("id", m.id)
    .put("data_origin", m.dataOrigin.packageName)
    .put("last_modified_time", m.lastModifiedTime.toString())

private fun at(time: Instant, off: ZoneOffset?, md: Metadata): JSONObject =
    JSONObject().put("time", iso(time, off)).put("metadata", meta(md))

private fun span(s: Instant, so: ZoneOffset?, e: Instant, eo: ZoneOffset?, md: Metadata): JSONObject =
    JSONObject().put("start_time", iso(s, so)).put("end_time", iso(e, eo)).put("metadata", meta(md))

private fun stageName(t: Int): String = when (t) {
    SleepSessionRecord.STAGE_TYPE_AWAKE -> "AWAKE"
    SleepSessionRecord.STAGE_TYPE_SLEEPING -> "SLEEPING"
    SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "OUT_OF_BED"
    SleepSessionRecord.STAGE_TYPE_LIGHT -> "LIGHT"
    SleepSessionRecord.STAGE_TYPE_DEEP -> "DEEP"
    SleepSessionRecord.STAGE_TYPE_REM -> "REM"
    SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "AWAKE_IN_BED"
    else -> "UNKNOWN"
}

private fun sleep(r: SleepSessionRecord): JSONObject {
    val stages = JSONArray()
    for (s in r.stages) {
        stages.put(
            JSONObject()
                .put("stage", stageName(s.stage))
                .put("start_time", iso(s.startTime, r.startZoneOffset))
                .put("end_time", iso(s.endTime, r.endZoneOffset))
                .put("duration_seconds", Duration.between(s.startTime, s.endTime).seconds),
        )
    }
    return span(r.startTime, r.startZoneOffset, r.endTime, r.endZoneOffset, r.metadata)
        .put("session_end_time", iso(r.endTime, r.endZoneOffset))
        .put("duration_seconds", Duration.between(r.startTime, r.endTime).seconds)
        .put("title", r.title ?: JSONObject.NULL)
        .put("stages", stages)
}

/** Heart rate is summarized per record: thousands of raw samples would be heavy and are rarely useful. */
private fun heartRate(r: HeartRateRecord): JSONObject? {
    if (r.samples.isEmpty()) return null
    val bpm = r.samples.map { it.beatsPerMinute }
    return span(r.startTime, r.startZoneOffset, r.endTime, r.endZoneOffset, r.metadata)
        .put("samples", bpm.size)
        .put("avg_bpm", bpm.average())
        .put("min_bpm", bpm.min())
        .put("max_bpm", bpm.max())
}

private fun nutrition(r: NutritionRecord): JSONObject {
    val o = span(r.startTime, r.startZoneOffset, r.endTime, r.endZoneOffset, r.metadata)
    o.put("name", r.name ?: JSONObject.NULL)
    o.put("meal_type", r.mealType)
    r.energy?.let { o.put("kilocalories", it.inKilocalories) }
    r.protein?.let { o.put("protein_g", it.inGrams) }
    r.totalCarbohydrate?.let { o.put("carbs_g", it.inGrams) }
    r.totalFat?.let { o.put("fat_g", it.inGrams) }
    r.dietaryFiber?.let { o.put("fiber_g", it.inGrams) }
    r.sugar?.let { o.put("sugar_g", it.inGrams) }
    r.sodium?.let { o.put("sodium_mg", it.inMilligrams) }
    return o
}

val SPECS: List<Spec<out Record>> = listOf(
    Spec("weight", WeightRecord::class) { at(it.time, it.zoneOffset, it.metadata).put("kilograms", it.weight.inKilograms) },
    Spec("body_fat", BodyFatRecord::class) { at(it.time, it.zoneOffset, it.metadata).put("percentage", it.percentage.value) },
    Spec("lean_body_mass", LeanBodyMassRecord::class) { at(it.time, it.zoneOffset, it.metadata).put("kilograms", it.mass.inKilograms) },
    Spec("height", HeightRecord::class) { at(it.time, it.zoneOffset, it.metadata).put("meters", it.height.inMeters) },
    Spec("basal_metabolic_rate", BasalMetabolicRateRecord::class) {
        at(it.time, it.zoneOffset, it.metadata).put("kilocalories_per_day", it.basalMetabolicRate.inKilocaloriesPerDay)
    },
    Spec("steps", StepsRecord::class) {
        span(it.startTime, it.startZoneOffset, it.endTime, it.endZoneOffset, it.metadata).put("count", it.count)
    },
    Spec("distance", DistanceRecord::class) {
        span(it.startTime, it.startZoneOffset, it.endTime, it.endZoneOffset, it.metadata).put("meters", it.distance.inMeters)
    },
    Spec("floors_climbed", FloorsClimbedRecord::class) {
        span(it.startTime, it.startZoneOffset, it.endTime, it.endZoneOffset, it.metadata).put("floors", it.floors)
    },
    Spec("active_calories_burned", ActiveCaloriesBurnedRecord::class) {
        span(it.startTime, it.startZoneOffset, it.endTime, it.endZoneOffset, it.metadata).put("kilocalories", it.energy.inKilocalories)
    },
    Spec("total_calories_burned", TotalCaloriesBurnedRecord::class) {
        span(it.startTime, it.startZoneOffset, it.endTime, it.endZoneOffset, it.metadata).put("kilocalories", it.energy.inKilocalories)
    },
    Spec("exercise", ExerciseSessionRecord::class) {
        span(it.startTime, it.startZoneOffset, it.endTime, it.endZoneOffset, it.metadata)
            .put("exercise_type", it.exerciseType)
            .put("title", it.title ?: JSONObject.NULL)
            .put("notes", it.notes ?: JSONObject.NULL)
            .put("duration_seconds", Duration.between(it.startTime, it.endTime).seconds)
    },
    Spec("sleep", SleepSessionRecord::class) { sleep(it) },
    Spec("heart_rate", HeartRateRecord::class) { heartRate(it) },
    Spec("resting_heart_rate", RestingHeartRateRecord::class) { at(it.time, it.zoneOffset, it.metadata).put("bpm", it.beatsPerMinute) },
    Spec("heart_rate_variability", HeartRateVariabilityRmssdRecord::class) {
        at(it.time, it.zoneOffset, it.metadata).put("rmssd_millis", it.heartRateVariabilityMillis)
    },
    Spec("oxygen_saturation", OxygenSaturationRecord::class) { at(it.time, it.zoneOffset, it.metadata).put("percentage", it.percentage.value) },
    Spec("respiratory_rate", RespiratoryRateRecord::class) { at(it.time, it.zoneOffset, it.metadata).put("rate", it.rate) },
    Spec("blood_pressure", BloodPressureRecord::class) {
        at(it.time, it.zoneOffset, it.metadata)
            .put("systolic_mmhg", it.systolic.inMillimetersOfMercury)
            .put("diastolic_mmhg", it.diastolic.inMillimetersOfMercury)
    },
    Spec("blood_glucose", BloodGlucoseRecord::class) { at(it.time, it.zoneOffset, it.metadata).put("mmol_per_liter", it.level.inMillimolesPerLiter) },
    Spec("body_temperature", BodyTemperatureRecord::class) { at(it.time, it.zoneOffset, it.metadata).put("celsius", it.temperature.inCelsius) },
    Spec("vo2_max", Vo2MaxRecord::class) { at(it.time, it.zoneOffset, it.metadata).put("ml_per_min_kg", it.vo2MillilitersPerMinuteKilogram) },
    Spec("nutrition", NutritionRecord::class) { nutrition(it) },
    Spec("hydration", HydrationRecord::class) {
        span(it.startTime, it.startZoneOffset, it.endTime, it.endZoneOffset, it.metadata).put("liters", it.volume.inLiters)
    },
)
