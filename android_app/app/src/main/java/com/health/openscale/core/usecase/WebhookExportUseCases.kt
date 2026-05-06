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
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Sends measurement data as a JSON POST request to a user-configured webhook URL.
 *
 * Triggered automatically on every new measurement insert. Can be configured by the user in Data
 * Management Settings.
 *
 * The JSON payload contains:
 * - `timestamp`: epoch milliseconds
 * - `id`: measurement id
 * - `userId`: user id
 * - one field per measurement value, keyed by the lowercase [MeasurementTypeKey] name (e.g.
 * `weight`, `body_fat`, `water`, `muscle`)
 */
@Singleton
class WebhookExportUseCases @Inject constructor(private val settingsFacade: SettingsFacade) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val keyById = MeasurementTypeKey.entries.associateBy { it.id }
    private val TAG = "WebhookExportUseCases"

    /** Sends a minimal test ping to [url] and returns a human-readable result string. */
    suspend fun sendTestRequest(url: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().apply {
                put("test", true)
                put("source", "openScale")
            }.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url).post(body).build()
            httpClient.newCall(request).execute().use { response ->
                "HTTP ${response.code} ${response.message}"
            }
        }.getOrElse { e -> "Error: ${e.localizedMessage}" }
    }

    suspend fun exportOnInsert(measurement: Measurement, values: List<MeasurementValue>) {
        val enabled = settingsFacade.webhookExportEnabled.first()
        if (!enabled) return

        val url = settingsFacade.webhookExportUrl.first()
        if (url.isNullOrBlank()) return

        val json = buildJson(measurement, values)

        withContext(Dispatchers.IO) {
            runCatching {
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url(url).post(body).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        LogManager.w(TAG, "Webhook POST failed: HTTP ${response.code} for $url")
                    } else {
                        LogManager.d(TAG, "Webhook POST succeeded: HTTP ${response.code}")
                    }
                }
            }
                    .onFailure { e -> LogManager.e(TAG, "Webhook POST error for $url", e) }
        }
    }

    private fun buildJson(measurement: Measurement, values: List<MeasurementValue>): JSONObject {
        val obj = JSONObject()
        obj.put("timestamp", measurement.timestamp)
        obj.put("id", measurement.id)
        obj.put("userId", measurement.userId)

        for (v in values) {
            val key = keyById[v.typeId]
            val fieldName =
                    if (key != null && key != MeasurementTypeKey.CUSTOM) {
                        key.name.lowercase()
                    } else {
                        "custom_${v.typeId}"
                    }
            when {
                v.floatValue != null -> obj.put(fieldName, v.floatValue.toDouble())
                v.intValue != null -> obj.put(fieldName, v.intValue)
                v.textValue != null -> obj.put(fieldName, v.textValue)
                v.dateValue != null -> obj.put(fieldName, v.dateValue)
            }
        }
        return obj
    }
}
