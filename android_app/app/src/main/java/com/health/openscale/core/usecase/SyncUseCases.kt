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

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.core.content.ContextCompat
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.utils.ConverterUtils
import com.health.openscale.core.utils.LogManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bundles sync trigger use cases for the external SyncService.
 *
 * Starts a foreground service via explicit component to notify external sync packages
 * about inserts/updates/deletes of measurements.
 *
 * Expected service component: `com.health.openscale.sync.core.service.SyncService`
 * in the target package (e.g. `com.health.openscale.sync`, `com.health.openscale.sync.oss`).
 */
@Singleton
class SyncUseCases @Inject constructor(
    private val application: Application,
    private val measurementTypeUseCases: MeasurementTypeCrudUseCases
) {
    private val _syncAppUnreachable = MutableSharedFlow<String>(extraBufferCapacity = 1)
    @Volatile private var lastUnreachableEmit = 0L


    /**
     * Triggers an **insert** sync event for [measurement] with its [values] to the given [pkgName].
     *
     * Extras:
     * - "mode" = "insert"
     * - "userId" = measurement.userId
     * - "date" = measurement.timestamp (epoch millis)
     * - optionally "weight", "fat", "water", "muscle" if present among [values]
     */
    suspend fun triggerSyncInsert(
        measurement: Measurement,
        values: List<MeasurementValue>,
        pkgName: String
    ): Result<Unit> = runCatching {
        val intent = Intent().apply {
            component = ComponentName(pkgName, SYNC_SERVICE_CLASS)
            putExtra("mode", "insert")
            putExtra("id", measurement.id)
            putExtra("userId", measurement.userId)
            putExtra("date", measurement.timestamp)
            putGenericValues(values)
        }
        startSyncService(pkgName, intent)
    }

    /**
     * Triggers an **update** sync event for [measurement] with its [values] to the given [pkgName].
     *
     * Extras:
     * - "mode" = "update"
     * - "userId" = measurement.userId
     * - "date" = measurement.timestamp (epoch millis)
     * - optionally "weight", "fat", "water", "muscle" if present among [values]
     */
    suspend fun triggerSyncUpdate(
        measurement: Measurement,
        values: List<MeasurementValue>,
        pkgName: String
    ): Result<Unit> = runCatching {
        val intent = Intent().apply {
            component = ComponentName(pkgName, SYNC_SERVICE_CLASS)
            putExtra("mode", "update")
            putExtra("id", measurement.id)
            putExtra("userId", measurement.userId)
            putExtra("date", measurement.timestamp)
            putGenericValues(values)
        }
        startSyncService(pkgName, intent)
    }

    /**
     * Triggers a **delete** sync event for the given [id] / [userId] + [date] to the target [pkgName].
     *
     * Extras:
     * - "mode" = "delete"
     * - "id" = stable measurement id (lets the sync side forget the exact ledger entry)
     * - "userId" = owning user id (for multi-user routing on the sync side)
     * - "date" = [Date.getTime] (epoch millis)
     */
    fun triggerSyncDelete(
        id: Int,
        userId: Int,
        date: Date,
        pkgName: String
    ): Result<Unit> = runCatching {
        val intent = Intent().apply {
            component = ComponentName(pkgName, SYNC_SERVICE_CLASS)
            putExtra("mode", "delete")
            putExtra("id", id)
            putExtra("userId", userId)
            putExtra("date", date.time)
        }
        startSyncService(pkgName, intent)
    }

    /**
     * Triggers a **clear** sync event to wipe the synced measurements of [userId] on [pkgName].
     *
     * Extras:
     * - "mode" = "clear"
     * - "userId" = the user whose measurements were cleared
     */
    fun triggerSyncClear(
        userId: Int,
        pkgName: String
    ): Result<Unit> = runCatching {
        val intent = Intent().apply {
            component = ComponentName(pkgName, SYNC_SERVICE_CLASS)
            putExtra("mode", "clear")
            putExtra("userId", userId)
        }
        startSyncService(pkgName, intent)
    }

    /**
     * Coalesced **changed** wake-up (no payload) for bulk operations (CSV import / backup restore):
     * the sync app reconciles its full state against openScale instead of receiving hundreds of
     * individual events. Avoids foreground-service spam.
     */
    fun triggerSyncChanged(
        pkgName: String
    ): Result<Unit> = runCatching {
        val intent = Intent().apply {
            component = ComponentName(pkgName, SYNC_SERVICE_CLASS)
            putExtra("mode", "changed")
        }
        startSyncService(pkgName, intent)
    }

    /** Convenience: fire a coalesced "changed" wake to all known sync app variants. */
    fun triggerSyncChangedAll() {
        SYNC_PACKAGES.forEach { triggerSyncChanged(it) }
    }

    /**
     * Emits the package name of a sync app that is **installed but could not be woken** (the
     * foreground-service start failed — typically because the OEM/user force-stopped it). The UI
     * shows an unobtrusive "please open openScale-sync" hint with an Open action ([openSyncApp]).
     */
    val syncAppUnreachable: SharedFlow<String> = _syncAppUnreachable.asSharedFlow()

    /** Launches an installed sync app (clears its FLAG_STOPPED so future pushes work again). */
    fun openSyncApp(pkgName: String) {
        runCatching {
            application.packageManager.getLaunchIntentForPackage(pkgName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?.let { application.startActivity(it) }
        }
    }

    // --- helpers ---

    /** Pre-check: only start a sync app that is actually installed (avoids FGS-start exceptions
     *  and log spam when a variant isn't present). Requires the <queries> entries in the manifest. */
    private fun isSyncAppInstalled(pkgName: String): Boolean = try {
        application.packageManager.getPackageInfo(pkgName, 0)
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Starts an installed sync app's foreground service. If the start fails although the app is
     * installed (force-stopped / blocked), emits [syncAppUnreachable] (rate-limited) so the UI can
     * nudge the user to open it.
     */
    private fun startSyncService(pkgName: String, intent: Intent) {
        if (!isSyncAppInstalled(pkgName)) return
        try {
            ContextCompat.startForegroundService(application.applicationContext, intent)
        } catch (e: Exception) {
            LogManager.w("SyncUseCases", "Sync app $pkgName installed but not startable (force-stopped?): ${e.message}")
            val now = System.currentTimeMillis()
            if (now - lastUnreachableEmit > 60 * 60 * 1000L) { // at most hourly
                lastUnreachableEmit = now
                _syncAppUnreachable.tryEmit(pkgName)
            }
        }
    }

    /**
     * Adds the full, self-describing generic value set as a JSON "values" extra (Phase 2): every
     * MeasurementValue with its type metadata (key/name/unit/inputType/isDerived), the numeric value
     * already converted to the dimension's canonical base unit, unit as a UCUM code. Custom types
     * ride along (key=="CUSTOM", distinguished by typeId). Lets the sync app forward all 34 types +
     * custom without knowing openScale's enums.
     */
    private suspend fun Intent.putGenericValues(values: List<MeasurementValue>) {
        val typesById = runCatching { measurementTypeUseCases.getAll() }.getOrNull()
            ?.associateBy { it.id } ?: return
        putExtra("values", GenericValueJson.build(values, typesById))
    }

    private companion object {
        const val SYNC_SERVICE_CLASS = "com.health.openscale.sync.core.service.SyncService"
        val SYNC_PACKAGES = listOf(
            "com.health.openscale.sync",
            "com.health.openscale.sync.oss",
            "com.health.openscale.sync.debug"
        )
    }
}

/**
 * Builds/parses the self-describing **generic value set** shared between the sync Intent
 * ([SyncUseCases]) and the ContentProvider ([com.health.openscale.core.database.DatabaseProvider]).
 *
 * Each value carries its type metadata (`typeId`, `key` = MeasurementTypeKey enum name, `name`,
 * `unit` as a UCUM code, `inputType`, `isDerived`); the numeric value is in the **canonical base
 * unit** of its dimension. Custom types ride along (key == "CUSTOM", distinguished by `typeId`).
 * HL7 FHIR Quantity / UCUM-inspired — the sync app forwards all types incl. custom without knowing
 * openScale's enums.
 */
object GenericValueJson {

    fun build(values: List<MeasurementValue>, typesById: Map<Int, MeasurementType>): String {
        val arr = JSONArray()
        for (v in values) {
            val type = typesById[v.typeId] ?: continue
            val obj = JSONObject()
            obj.put("typeId", type.id)
            obj.put("key", type.key.name)
            obj.put("name", type.name ?: type.key.name)
            obj.put("unit", ucumCode(type.unit))
            obj.put("inputType", type.inputType.name)
            obj.put("isDerived", type.isDerived)
            when (type.inputType) {
                InputFieldType.FLOAT, InputFieldType.INT -> {
                    val raw = v.floatValue ?: v.intValue?.toFloat()
                    if (raw != null) {
                        obj.put("value", ConverterUtils.convertFloatValueUnit(raw, type.unit, canonicalUnit(type.unit)).toDouble())
                    }
                }
                InputFieldType.TEXT -> v.textValue?.let { obj.put("text", it) }
                InputFieldType.DATE, InputFieldType.TIME -> v.dateValue?.let { obj.put("text", it.toString()) }
                else -> {}
            }
            arr.put(obj)
        }
        return arr.toString()
    }

    /**
     * Reverse of [build] (inbound): parse a generic value JSON into (typeId, valueInUserUnit) pairs.
     * Predefined types are matched by [typesByKey] (enum name), custom by [typesById] (typeId).
     */
    fun parse(
        json: String,
        typesByKey: Map<String, MeasurementType>,
        typesById: Map<Int, MeasurementType>
    ): List<Pair<Int, Float>> {
        val out = mutableListOf<Pair<Int, Float>>()
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (!o.has("value")) continue
                val key = o.optString("key", "")
                val type = if (key == "CUSTOM") typesById[o.optInt("typeId", -1)] else typesByKey[key]
                if (type == null) continue
                val canonical = o.getDouble("value").toFloat()
                val userValue = ConverterUtils.convertFloatValueUnit(canonical, canonicalUnit(type.unit), type.unit)
                out.add(type.id to userValue)
            }
        }
        return out
    }

    /** Canonical base unit per dimension (everything is converted to this before sending). */
    fun canonicalUnit(u: UnitType): UnitType = when (u) {
        UnitType.LB, UnitType.ST -> UnitType.KG
        UnitType.INCH -> UnitType.CM
        else -> u
    }

    /** UCUM code for the canonical unit of the given unit's dimension. */
    fun ucumCode(u: UnitType): String = when (u) {
        UnitType.KG, UnitType.LB, UnitType.ST -> "kg"
        UnitType.PERCENT -> "%"
        UnitType.CM, UnitType.INCH -> "cm"
        UnitType.KCAL -> "kcal"
        UnitType.BPM -> "/min"
        UnitType.OHM -> "Ohm"
        UnitType.NONE -> ""
    }
}
