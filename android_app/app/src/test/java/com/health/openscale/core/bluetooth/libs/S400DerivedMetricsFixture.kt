package com.health.openscale.core.bluetooth.libs

import java.io.File
import kotlin.math.abs


/**
 * Runs the pure derived-metrics pipeline against recorded Xiaomi responses,
 * supplied as a dependency-free TSV (no Android, no org.json).
 *
 * Columns: label h_cm w_kg sex bfp bfm ffm bmr bmc slm slp bwm bwp smm swt
 */
fun verifyS400DerivedMetricsFixture(lines: Sequence<String>): Int {
    var failures = 0
    var checked = 0
    lines.forEach { line ->
        val t = line.split('\t')
        require(t.size == 15) { "Malformed fixture row: expected 15 columns, got ${t.size}" }
        val label = t[0]
        val h = t[1].toDouble()
        val w = t[2].toDouble()
        val sex = t[3] == "1"
        val r = S400DerivedMetrics.compute(
            heightCm = h.toFloat(), weightKg = w.toFloat(), sexMale = sex,
            components = S400DerivedMetrics.Components(
                bodyFatPct = t[4].toFloat(),
                boneKg = t[8].toFloat(),
                waterPct = t[12].toFloat(),
                skeletalMuscleKg = t[13].toFloat(),
            ),
        )
        val checks = linkedMapOf<String, Double>(
            "bfp" to r.bfPct.toDouble(), "bfm" to r.bfKg.toDouble(), "ffm" to r.ffmKg.toDouble(),
            "bmr" to r.bmrKcal.toDouble(), "bmc" to (r.boneKg ?: -1f).toDouble(),
            "slm" to (r.slmKg ?: -1f).toDouble(), "bwm" to (r.waterKg ?: -1f).toDouble(),
            "bwp" to (r.waterPct ?: -1f).toDouble(),
            "smm" to (r.skeletalMuscleKg ?: -1f).toDouble(),
            "swt" to r.idealWeightKg.toDouble(),
        )
        val actual = doubleArrayOf(t[4].toDouble(), t[5].toDouble(), t[6].toDouble(),
            t[7].toDouble(), t[8].toDouble(), t[9].toDouble(), t[11].toDouble(),
            t[12].toDouble(), t[13].toDouble(), t[14].toDouble())
        val names = arrayOf("bfp", "bfm", "ffm", "bmr", "bmc", "slm", "bwm", "bwp", "smm", "swt")
        checked++
        names.forEachIndexed { i, metric ->
            val predicted = checks[metric]!!
            if (abs(predicted - actual[i]) > 0.0005) {
                failures++
                println("FAIL $label $metric predicted=$predicted actual=${actual[i]}")
            }
        }
    }
    println("checked=$checked failures=$failures")
    check(checked > 0) { "Empty fixture" }
    check(failures == 0) { "$failures metric mismatches in $checked responses" }
    return checked
}

fun main(args: Array<String>) {
    require(args.size == 1) { "Expected one TSV fixture path" }
    File(args[0]).useLines { verifyS400DerivedMetricsFixture(it) }
}
