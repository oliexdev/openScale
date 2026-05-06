/*
 * openScale
 * Copyright (C) 2025 olie.xdev <olie.xdeveloper@googlemail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.usecase

import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.utils.LogManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Exports measurement data to an InfluxDB instance using the HTTP Line Protocol API.
 *
 * Supports InfluxDB v1 (POST /write?db=...) and v2 (POST /api/v2/write?org=...&bucket=...). Field
 * names for the 7 main body metrics are configurable by the user. Other numeric fields are sent
 * with their default openScale key names.
 *
 * Triggered automatically on every new measurement insert when enabled.
 */
@Singleton
class InfluxDbExportUseCases @Inject constructor(private val settingsFacade: SettingsFacade) {
    private val httpClient =
            OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
    private val TAG = "InfluxDbExportUseCases"

    suspend fun exportOnInsert(measurement: Measurement, values: List<MeasurementValue>) {
        val enabled = settingsFacade.influxDbExportEnabled.first()
        if (!enabled) return

        val host = settingsFacade.influxDbHost.first()
        if (host.isBlank()) return

        val lineProtocol = buildLineProtocol(measurement, values)
        if (lineProtocol.isBlank()) return

        withContext(Dispatchers.IO) {
            runCatching {
                val url = buildWriteUrl(host)
                val requestBuilder =
                        Request.Builder()
                                .url(url)
                                .post(
                                        lineProtocol.toRequestBody(
                                                "text/plain; charset=utf-8".toMediaType()
                                        )
                                )

                val username = settingsFacade.influxDbUsername.first()
                val password = settingsFacade.influxDbPassword.first()
                if (username.isNotBlank()) {
                    requestBuilder.header("Authorization", Credentials.basic(username, password))
                }

                httpClient.newCall(requestBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        LogManager.w(TAG, "InfluxDB POST failed: HTTP ${response.code} for $url")
                    } else {
                        LogManager.d(TAG, "InfluxDB POST succeeded: HTTP ${response.code}")
                    }
                }
            }
                    .onFailure { e -> LogManager.e(TAG, "InfluxDB POST error", e) }
        }
    }

    /** Sends a test point and returns a human-readable result string. */
    suspend fun sendTestRequest(): String =
            withContext(Dispatchers.IO) {
                runCatching {
                    val host = settingsFacade.influxDbHost.first()
                    if (host.isBlank()) return@withContext "Error: Host is not configured"

                    val measurement = settingsFacade.influxDbMeasurement.first()
                    val lineProtocol =
                            "${escapeTag(measurement)},source=openscale test=1i ${System.currentTimeMillis()}"
                    val url = buildWriteUrl(host)

                    val requestBuilder =
                            Request.Builder()
                                    .url(url)
                                    .post(
                                            lineProtocol.toRequestBody(
                                                    "text/plain; charset=utf-8".toMediaType()
                                            )
                                    )

                    val username = settingsFacade.influxDbUsername.first()
                    val password = settingsFacade.influxDbPassword.first()
                    if (username.isNotBlank()) {
                        requestBuilder.header(
                                "Authorization",
                                Credentials.basic(username, password)
                        )
                    }

                    httpClient.newCall(requestBuilder.build()).execute().use { response ->
                        "HTTP ${response.code} ${response.message}"
                    }
                }
                        .getOrElse { e -> "Error: ${e.localizedMessage}" }
            }

    private suspend fun buildWriteUrl(host: String): String {
        val cleanHost = host.trimEnd('/')
        return when (settingsFacade.influxDbVersion.first()) {
            "v2" -> "$cleanHost/api/v2/write?precision=ms"
            else -> {
                val database = settingsFacade.influxDbDatabase.first()
                "$cleanHost/write?db=${encodeQueryParam(database)}&precision=ms"
            }
        }
    }

    private suspend fun buildLineProtocol(
            measurement: Measurement,
            values: List<MeasurementValue>
    ): String {
        val measurementName = settingsFacade.influxDbMeasurement.first()

        val keyById = MeasurementTypeKey.entries.associateBy { it.id }
        val fields =
                values.mapNotNull { v ->
                    val typeKey = keyById[v.typeId] ?: return@mapNotNull null
                    // Skip non-numeric / metadata fields
                    if (typeKey in
                                    setOf(
                                            MeasurementTypeKey.DATE,
                                            MeasurementTypeKey.TIME,
                                            MeasurementTypeKey.COMMENT,
                                            MeasurementTypeKey.USER,
                                            MeasurementTypeKey.CUSTOM
                                    )
                    )
                            return@mapNotNull null

                    val fieldName = typeKey.name.lowercase()

                    when {
                        v.floatValue != null -> "$fieldName=${v.floatValue}"
                        v.intValue != null -> "${fieldName}=${v.intValue}i"
                        else -> null
                    }
                }

        if (fields.isEmpty()) return ""

        // Line Protocol: <measurement>,<tags> <fields> <timestamp>
        return "${escapeTag(measurementName)},source=openscale ${fields.joinToString(",")} ${measurement.timestamp}"
    }

    /** Escapes special characters in InfluxDB tag keys/values and measurement names. */
    private fun escapeTag(value: String): String =
            value.replace(",", "\\,").replace(" ", "\\ ").replace("=", "\\=")

    /** Simple percent-encoding for query parameter values. */
    private fun encodeQueryParam(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}
