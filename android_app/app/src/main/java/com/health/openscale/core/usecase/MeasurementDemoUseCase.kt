package com.health.openscale.core.usecase

import android.content.Context
import androidx.annotation.StringRes
import com.health.openscale.R
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.MeasurementValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Use case for generating mock measurement data for developer testing.
 *
 * Each scenario is designed to exercise a specific part of the insights engine:
 * - Pattern classification (all 8 CompositionPatternType values)
 * - Anomaly detection (z-score spikes)
 * - History chips (requires >450 days of data)
 * - Weekday pattern (requires ≥5 measurements per weekday)
 * - Seasonal pattern (requires ≥2 years)
 * - Edge cases (sparse data, missing metrics)
 */
@Singleton
class MeasurementDemoUseCase @Inject constructor(
    private val query: MeasurementQueryUseCases,
    private val crud: MeasurementCrudUseCases,
) {
    enum class DemoScenario(@StringRes val labelRes: Int) {
        // ── Pattern classification ─────────────────────────────────────────
        /** fat↓ muscle↑ → RECOMPOSITION — point lands TOP-LEFT */
        RECOMPOSITION(R.string.dev_tools_scenario_recomposition),

        /** fat stable muscle↑ → MUSCLE_GAIN — point lands top-center */
        MUSCLE_GAIN(R.string.dev_tools_scenario_muscle_gain),

        /** fat↑ muscle↑ → MUSCLE_AND_FAT_GAIN — point lands TOP-RIGHT (Bulk) */
        BULK(R.string.dev_tools_scenario_bulk),

        /** fat↓ muscle stable → FAT_LOSS — point lands left-center */
        FAT_LOSS(R.string.dev_tools_scenario_fat_loss),

        /** fat↑ muscle stable → FAT_GAIN — point lands right-center */
        FAT_GAIN(R.string.dev_tools_scenario_fat_gain),

        /** fat↓ muscle↓ → WEIGHT_LOSS_MIXED — point lands BOTTOM-LEFT */
        MIXED_LOSS(R.string.dev_tools_scenario_mixed_loss),

        /** all stable → STABLE — point lands center */
        STABLE(R.string.dev_tools_scenario_stable),

        // ── Insights edge cases ───────────────────────────────────────────
        /** Linear progress over time — tests BodyCompositionShift best-month, rate, plateau */
        TREND_PROGRESS(R.string.dev_tools_scenario_trend),

        /** Includes deliberate z-score spikes — tests anomaly detection */
        WITH_ANOMALIES(R.string.dev_tools_scenario_with_anomalies),

        /** 2 years of data with seasonal weight variation — tests SeasonalPattern */
        SEASONAL(R.string.dev_tools_scenario_seasonal),

        /** Daily measurements — tests WeekdayPattern (needs ≥5 per weekday) */
        WEEKDAY_HABITS(R.string.dev_tools_scenario_weekday_habits),

        /** Only weight measured, no fat/muscle/water — tests INSUFFICIENT pattern */
        SPARSE_METRICS(R.string.dev_tools_scenario_sparse_metrics),

        /** Measurement gaps >30 days — tests anomaly baseline reset */
        WITH_GAPS(R.string.dev_tools_scenario_with_gaps);

        fun getDisplayName(context: Context): String = context.getString(labelRes)
    }

    enum class TimeRange(val days: Int, @StringRes val labelRes: Int) {
        LAST_30_DAYS(30, R.string.dev_tools_range_30_days),
        LAST_6_MONTHS(180, R.string.dev_tools_range_6_months),
        LAST_2_YEARS(730, R.string.dev_tools_range_2_years),
        LAST_4_YEARS(1460, R.string.dev_tools_range_4_years);

        fun getDisplayName(context: Context): String = context.getString(labelRes)
    }

    suspend fun generate(
        userId: Int,
        scenario: DemoScenario,
        range: TimeRange,
        wipeExisting: Boolean,
    ) = withContext(Dispatchers.Default) {
        if (wipeExisting) {
            val existing = query.getMeasurementsForUser(userId).first()
            existing.forEach { crud.deleteMeasurement(it.measurement) }
        }

        val count = when (range) {
            TimeRange.LAST_30_DAYS  -> 15
            TimeRange.LAST_6_MONTHS -> 75
            TimeRange.LAST_2_YEARS  -> 300
            TimeRange.LAST_4_YEARS  -> 600
        }

        when (scenario) {
            // fat↓ muscle↑ — RECOMPOSITION
            DemoScenario.RECOMPOSITION ->
                generateScenario(userId, count, range.days,
                    init        = Triple(80f, 25f, 35f),
                    stepWeight  = -0.03f..0.03f,
                    stepFat     = -0.12f..0.01f,
                    stepMuscle  = 0.05f..0.15f,
                )

            // fat stable muscle↑ — MUSCLE_GAIN
            DemoScenario.MUSCLE_GAIN ->
                generateScenario(userId, count, range.days,
                    init        = Triple(78f, 18f, 38f),
                    stepWeight  = 0.02f..0.08f,
                    stepFat     = -0.02f..0.02f,
                    stepMuscle  = 0.06f..0.14f,
                )

            // fat↑ muscle↑ — MUSCLE_AND_FAT_GAIN (Bulk)
            DemoScenario.BULK ->
                generateScenario(userId, count, range.days,
                    init        = Triple(75f, 15f, 40f),
                    stepWeight  = 0.08f..0.18f,
                    stepFat     = 0.04f..0.12f,
                    stepMuscle  = 0.05f..0.12f,
                )

            // fat↓ muscle stable — FAT_LOSS
            DemoScenario.FAT_LOSS ->
                generateScenario(userId, count, range.days,
                    init        = Triple(90f, 30f, 35f),
                    stepWeight  = -0.10f..-0.02f,
                    stepFat     = -0.12f..-0.02f,
                    stepMuscle  = -0.02f..0.02f,
                )

            // fat↑ muscle stable — FAT_GAIN
            DemoScenario.FAT_GAIN ->
                generateScenario(userId, count, range.days,
                    init        = Triple(75f, 18f, 38f),
                    stepWeight  = 0.05f..0.15f,
                    stepFat     = 0.05f..0.14f,
                    stepMuscle  = -0.02f..0.02f,
                )

            // fat↓ muscle↓ — WEIGHT_LOSS_MIXED
            DemoScenario.MIXED_LOSS ->
                generateScenario(userId, count, range.days,
                    init        = Triple(95f, 32f, 30f),
                    stepWeight  = -0.15f..-0.05f,
                    stepFat     = -0.08f..-0.01f,
                    stepMuscle  = -0.08f..-0.01f,
                )

            // all stable — STABLE
            DemoScenario.STABLE ->
                generateScenario(userId, count, range.days,
                    init        = Triple(75f, 20f, 40f),
                    stepWeight  = -0.02f..0.02f,
                    stepFat     = -0.02f..0.02f,
                    stepMuscle  = -0.02f..0.02f,
                )

            // Linear improvement — tests shift analytics
            DemoScenario.TREND_PROGRESS ->
                generateTrendProgress(userId, count, range.days)

            // Deliberate spikes every ~25 measurements — tests anomaly detection
            DemoScenario.WITH_ANOMALIES ->
                generateWithAnomalies(userId, count, range.days)

            // Summer high / winter low weight cycle — tests SeasonalPattern
            DemoScenario.SEASONAL ->
                generateSeasonal(userId, count, range.days)

            // Daily cadence — tests WeekdayPattern
            DemoScenario.WEEKDAY_HABITS ->
                generateWeekdayHabits(userId, range.days)

            // Weight only, no fat/muscle/water — tests INSUFFICIENT pattern confidence
            DemoScenario.SPARSE_METRICS ->
                generateSparseMetrics(userId, count, range.days)

            // Measurement gaps >30 days — tests anomaly baseline reset
            DemoScenario.WITH_GAPS ->
                generateWithGaps(userId, range.days)
        }
    }

    // ── Scenario implementations ──────────────────────────────────────────────

    private suspend fun generateScenario(
        userId: Int,
        count: Int,
        daysBack: Int,
        init: Triple<Float, Float, Float>,
        stepWeight: ClosedFloatingPointRange<Float>,
        stepFat: ClosedFloatingPointRange<Float>,
        stepMuscle: ClosedFloatingPointRange<Float>,
    ) {
        val types = enabledNonDerivedTypes()
        val now   = System.currentTimeMillis()
        var (bw, bf, bm) = init

        for (i in count downTo 0) {
            val timestamp = now - (i.toFloat() / count * daysBack * MS_PER_DAY).toLong()
            bw += stepWeight.random()
            bf += stepFat.random()
            bm += stepMuscle.random()
            saveMock(userId, timestamp, types, bw, bf, bm, i)
        }
    }

    private suspend fun generateTrendProgress(userId: Int, count: Int, daysBack: Int) {
        val types  = enabledNonDerivedTypes()
        val now    = System.currentTimeMillis()
        val start  = Triple(100f, 35f, 28f)
        val target = Triple(78f, 18f, 40f)

        for (i in 0..count) {
            val t         = i.toFloat() / count
            val timestamp = now - ((1f - t) * daysBack * MS_PER_DAY).toLong()
            saveMock(
                userId, timestamp, types,
                w = lerp(start.first,  target.first,  t) + noise(-0.3f, 0.3f),
                f = lerp(start.second, target.second, t) + noise(-0.2f, 0.2f),
                m = lerp(start.third,  target.third,  t) + noise(-0.1f, 0.1f),
                index = i,
            )
        }
    }

    /**
     * Generates a stable baseline with deliberate z-score spikes every [spikeInterval]
     * measurements to reliably trigger anomaly detection.
     * Spike magnitude is 5× the normal noise — well above the z-score threshold of 3.0.
     */
    private suspend fun generateWithAnomalies(userId: Int, count: Int, daysBack: Int) {
        val types         = enabledNonDerivedTypes()
        val now           = System.currentTimeMillis()
        val spikeInterval = 25
        var bw = 80f; var bf = 22f; var bm = 36f

        for (i in count downTo 0) {
            val timestamp = now - (i.toFloat() / count * daysBack * MS_PER_DAY).toLong()
            val isSpike   = (count - i) % spikeInterval == 0 && (count - i) > 0

            bw += if (isSpike) noise(2.5f, 4.0f)  else noise(-0.1f, 0.1f)
            bf += if (isSpike) noise(1.5f, 3.0f)  else noise(-0.05f, 0.05f)
            bm += noise(-0.05f, 0.05f)

            saveMock(userId, timestamp, types, bw, bf, bm, i)
        }
    }

    /**
     * Generates weight that follows a sinusoidal seasonal curve — higher in winter,
     * lower in summer — to reliably produce a SeasonalPattern with highestMonth/lowestMonth.
     * Requires at least LAST_2_YEARS to reach InsightConfidence.HIGH.
     */
    private suspend fun generateSeasonal(userId: Int, count: Int, daysBack: Int) {
        val types  = enabledNonDerivedTypes()
        val now    = System.currentTimeMillis()
        var bf     = 22f
        var bm     = 36f

        for (i in count downTo 0) {
            val timestamp  = now - (i.toFloat() / count * daysBack * MS_PER_DAY).toLong()
            // Sinusoidal weight: peaks in January (winter), dips in July (summer)
            val dayOfYear  = (i.toFloat() / count * daysBack) % 365f
            val seasonal   = kotlin.math.sin(2f * Math.PI.toFloat() * dayOfYear / 365f) * 3f
            val bw         = 80f + seasonal + noise(-0.3f, 0.3f)
            bf             += noise(-0.03f, 0.03f)
            bm             += noise(-0.02f, 0.02f)
            saveMock(userId, timestamp, types, bw, bf, bm, i)
        }
    }

    /**
     * Generates one measurement per day with day-of-week weight offsets to produce
     * a clear WeekdayPattern. Monday tends high (post-weekend), Friday tends low.
     * Requires at least LAST_6_MONTHS to reach InsightConfidence.HIGH (≥5 per weekday).
     */
    private suspend fun generateWeekdayHabits(userId: Int, daysBack: Int) {
        val types = enabledNonDerivedTypes()
        val now   = System.currentTimeMillis()
        // Signed offset per weekday (0=Mon..6=Sun) in kg — simulates weekend eating patterns
        val weekdayOffset = floatArrayOf(0.4f, 0.2f, 0.0f, -0.1f, -0.3f, 0.1f, 0.5f)
        var bw = 78f; var bf = 21f; var bm = 37f

        for (dayAgo in daysBack downTo 0) {
            val timestamp  = now - dayAgo * MS_PER_DAY.toLong()
            val dayOfWeek  = ((System.currentTimeMillis() / MS_PER_DAY - dayAgo) % 7).toInt()
            val offset     = weekdayOffset[((dayOfWeek % 7) + 7) % 7]
            bw            += noise(-0.05f, 0.05f)
            bf            += noise(-0.02f, 0.02f)
            bm            += noise(-0.01f, 0.01f)
            saveMock(userId, timestamp, types, bw + offset, bf, bm, dayAgo)
        }
    }

    /**
     * Generates measurements with only weight recorded — no fat, muscle, or water.
     * Tests that BodyCompositionPattern returns INSUFFICIENT confidence and the
     * placeholder card is shown instead of the scatter plot.
     */
    private suspend fun generateSparseMetrics(userId: Int, count: Int, daysBack: Int) {
        // Deliberately use only weight type to simulate incomplete scale data
        val weightType = query.getAllMeasurementTypes().first()
            .firstOrNull { it.key == MeasurementTypeKey.WEIGHT && it.isEnabled }
            ?: return

        val now = System.currentTimeMillis()
        var bw  = 82f

        for (i in count downTo 0) {
            val timestamp   = now - (i.toFloat() / count * daysBack * MS_PER_DAY).toLong()
            bw             += noise(-0.2f, 0.2f)
            val measurement = Measurement(userId = userId, timestamp = timestamp)
            val value       = MeasurementValue(measurementId = 0, typeId = weightType.id, floatValue = bw)
            crud.saveMeasurement(measurement, listOf(value))
        }
    }

    /**
     * Generates three measurement clusters separated by >30-day gaps to test
     * that the anomaly detector resets its baseline after each gap and does not
     * produce false positives at cluster boundaries.
     */
    private suspend fun generateWithGaps(userId: Int, daysBack: Int) {
        val types = enabledNonDerivedTypes()
        val now   = System.currentTimeMillis()

        // Three clusters: each 30 days long, separated by 45-day gaps
        val clusters = listOf(
            (daysBack downTo daysBack - 29),   // oldest cluster
            ((daysBack - 75) downTo daysBack - 104),  // middle cluster
            (29 downTo 0),                     // recent cluster
        )

        var bw = 85f; var bf = 24f; var bm = 34f

        clusters.forEach { range ->
            // Each cluster has a different baseline to produce a clear shift at boundaries
            bw += noise(-1f, 1f)
            range.forEach { dayAgo ->
                val timestamp = now - dayAgo * MS_PER_DAY.toLong()
                bw += noise(-0.15f, 0.15f)
                bf += noise(-0.05f, 0.05f)
                bm += noise(-0.03f, 0.03f)
                saveMock(userId, timestamp, types, bw, bf, bm, dayAgo)
            }
        }
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private suspend fun enabledNonDerivedTypes(): List<MeasurementType> =
        query.getAllMeasurementTypes().first().filter { it.isEnabled && !it.isDerived }

    private suspend fun saveMock(
        userId: Int,
        ts: Long,
        types: List<MeasurementType>,
        w: Float, f: Float, m: Float,
        index: Int,
    ) {
        val measurement = Measurement(userId = userId, timestamp = ts)
        val values = types.mapNotNull { type ->
            when (type.inputType) {
                InputFieldType.TEXT -> {
                    if (index % 12 == 0)
                        MeasurementValue(measurementId = 0, typeId = type.id,
                            textValue = "Demo comment #$index")
                    else null
                }
                InputFieldType.INT -> {
                    val v = when (type.key) {
                        MeasurementTypeKey.HEART_RATE -> (60..85).random()
                        else                          -> (10..100).random()
                    }
                    MeasurementValue(measurementId = 0, typeId = type.id, intValue = v)
                }
                InputFieldType.FLOAT -> {
                    val v = when (type.key) {
                        MeasurementTypeKey.WEIGHT       -> w
                        MeasurementTypeKey.BODY_FAT     -> f.coerceIn(3f, 50f)
                        MeasurementTypeKey.MUSCLE       -> m.coerceIn(10f, 75f)
                        MeasurementTypeKey.WATER        -> (100f - f - noise(1f, 4f)).coerceIn(40f, 75f)
                        MeasurementTypeKey.BONE         -> (w * 0.04f + noise(-0.2f, 0.2f)).coerceIn(1f, 10f)
                        MeasurementTypeKey.VISCERAL_FAT -> (f / 3f + noise(-1f, 1f)).coerceIn(1f, 20f)
                        MeasurementTypeKey.WAIST,
                        MeasurementTypeKey.HIPS,
                        MeasurementTypeKey.CHEST,
                        MeasurementTypeKey.THIGH,
                        MeasurementTypeKey.BICEPS,
                        MeasurementTypeKey.NECK         -> (w * 1.1f + noise(-2f, 2f)).coerceIn(20f, 150f)
                        else                            -> w * 0.5f
                    }
                    MeasurementValue(measurementId = 0, typeId = type.id, floatValue = v)
                }
                else -> null
            }
        }
        crud.saveMeasurement(measurement, values)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun noise(min: Float, max: Float) = min + Random.nextFloat() * (max - min)
    private fun ClosedFloatingPointRange<Float>.random() =
        start + Random.nextFloat() * (endInclusive - start)

    companion object {
        private const val MS_PER_DAY = 86_400_000L
    }
}