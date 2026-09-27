/*
 * openScale
 * Copyright (C) 2026 Dany Mestas
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.bluetooth.libs

import java.util.zip.GZIPInputStream

/**
 * NHANES 1999-2004 adults 18-49 with bioimpedance spectroscopy and DXA, from
 * `src/test/resources/s400/nhanes_1999_2004.csv.gz`; `make_nhanes_fixture.py`
 * next to it documents the source files, filters and columns.
 */
object S400NhanesFixture {

    class Row(
        val cycle: String, val male: Boolean, val age: Int, val h: Float, val w: Float,
        val z50: Float, val z250: Float, val bfDxa: Double, val re: Float,
        val ecf: Float?, val tbw: Float?,
    ) {
        val inputs get() = S400Inputs(age, male, h, w, rHighRaw = z250, rLowRaw = z50)
    }

    val rows: List<Row> by lazy {
        val stream = S400NhanesFixture::class.java.getResourceAsStream("/s400/nhanes_1999_2004.csv.gz")!!
        GZIPInputStream(stream).bufferedReader().useLines { lines ->
            lines.drop(1).map { line ->
                val f = line.split(',')
                Row(f[0], f[1] == "1", f[2].toInt(), f[3].toFloat(), f[4].toFloat(),
                    f[5].toFloat(), f[6].toFloat(), f[7].toDouble(), f[8].toFloat(),
                    f[9].toFloatOrNull(), f[10].toFloatOrNull())
            }.toList()
        }
    }
}
