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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.bluetooth

import android.content.Context
import com.health.openscale.core.bluetooth.scales.AAAxHandler
import com.health.openscale.core.bluetooth.scales.ActiveEraBF06Handler
import com.health.openscale.core.bluetooth.scales.BeurerSanitasHandler
import com.health.openscale.core.bluetooth.scales.BroadcastScaleAdapter
import com.health.openscale.core.bluetooth.scales.CustomOpenScaleHandler
import com.health.openscale.core.bluetooth.scales.DebugGattHandler
import com.health.openscale.core.bluetooth.scales.DeviceSupport
import com.health.openscale.core.bluetooth.scales.DigooDGSO38HHandler
import com.health.openscale.core.bluetooth.scales.ESCS20mHandler
import com.health.openscale.core.bluetooth.scales.EufyC1P1Handler
import com.health.openscale.core.bluetooth.scales.ExcelvanCF36xHandler
import com.health.openscale.core.bluetooth.scales.ExingtechY1Handler
import com.health.openscale.core.bluetooth.scales.GattScaleAdapter
import com.health.openscale.core.bluetooth.scales.HesleyHandler
import com.health.openscale.core.bluetooth.scales.HoffenBbs8107Handler
import com.health.openscale.core.bluetooth.scales.HuaweiAH100Handler
import com.health.openscale.core.bluetooth.scales.IHealthHS3Handler
import com.health.openscale.core.bluetooth.scales.InlifeHandler
import com.health.openscale.core.bluetooth.scales.LinkMode
import com.health.openscale.core.bluetooth.scales.MGBHandler
import com.health.openscale.core.bluetooth.scales.MedisanaBs44xHandler
import com.health.openscale.core.bluetooth.scales.MiScaleHandler
import com.health.openscale.core.bluetooth.scales.OkOkHandler
import com.health.openscale.core.bluetooth.scales.OneByoneHandler
import com.health.openscale.core.bluetooth.scales.OneByoneNewHandler
import com.health.openscale.core.bluetooth.scales.QNHandler
import com.health.openscale.core.bluetooth.scales.RenphoES26BBHandler
import com.health.openscale.core.bluetooth.scales.RenphoHandler
import com.health.openscale.core.bluetooth.scales.SanitasSbf72Handler
import com.health.openscale.core.bluetooth.scales.ScaleDeviceHandler
import com.health.openscale.core.bluetooth.scales.SenssunHandler
import com.health.openscale.core.bluetooth.scales.SinocareHandler
import com.health.openscale.core.bluetooth.scales.SoehnleHandler
import com.health.openscale.core.bluetooth.scales.SppScaleAdapter
import com.health.openscale.core.bluetooth.scales.StandardBeurerSanitasHandler
import com.health.openscale.core.bluetooth.scales.TrisaBodyAnalyzeHandler
import com.health.openscale.core.bluetooth.scales.TuningProfile
import com.health.openscale.core.bluetooth.scales.YunmaiHandler
import com.health.openscale.core.facade.MeasurementFacade
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.facade.UserFacade
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.LogManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Factory class responsible for creating appropriate [ScaleCommunicator] instances
 * for different Bluetooth scale devices.
 */
@Singleton
class ScaleFactory @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val settingsFacade: SettingsFacade,
    private val measurementFacade: MeasurementFacade,
    private val userFacade: UserFacade,
) {
    private val TAG = "ScaleHandlerFactory"

    // List of modern Kotlin-based device handlers.
    private val modernKotlinHandlers: List<ScaleDeviceHandler> = listOf(
        YunmaiHandler(isMini = false),
        YunmaiHandler(isMini = true),
        TrisaBodyAnalyzeHandler(),
        SanitasSbf72Handler(),
        StandardBeurerSanitasHandler(),
        SoehnleHandler(),
        SinocareHandler(),
        SenssunHandler(),
        RenphoHandler(),
        QNHandler(),
        OneByoneHandler(),
        OneByoneNewHandler(),
        OkOkHandler(),
        MiScaleHandler(),
        MGBHandler(),
        MedisanaBs44xHandler(),
        EufyC1P1Handler(),
        InlifeHandler(),
        IHealthHS3Handler(),
        HuaweiAH100Handler(),
        HoffenBbs8107Handler(),
        HesleyHandler(),
        ExingtechY1Handler(),
        ExcelvanCF36xHandler(),
        ESCS20mHandler(),
        RenphoES26BBHandler(),
        DigooDGSO38HHandler(),
        DebugGattHandler(),
        CustomOpenScaleHandler(),
        BeurerSanitasHandler(),
        AAAxHandler(),
        ActiveEraBF06Handler(),
        )

    /**
     * Creates a [ScaleCommunicator] based on a modern [ScaleDeviceHandler].
     * This method is conceptual for now, as the current DummyScaleHandlers are not full communicators.
     * In a full implementation, this might return the handler itself if it's a ScaleCommunicator,
     * or wrap it in a modern adapter.
     *
     * @param handler The [ScaleDeviceHandler] that can handle the device.
     * @return A [ScaleCommunicator] instance if one can be provided by or for the handler, otherwise null.
     */
    private fun createModernCommunicator(
        handler: ScaleDeviceHandler,
        support: DeviceSupport
    ): ScaleCommunicator? {
        // Resolve effective tuning: prefer user-saved value, fall back to handler default
        val effectiveTuning: TuningProfile = run {
            val saved: String? = runCatching {
                runBlocking(Dispatchers.IO) {
                    withTimeout(250) {
                        settingsFacade.savedBluetoothTuneProfile.firstOrNull()
                    }
                }
            }.getOrNull()

            saved?.let { runCatching { TuningProfile.valueOf(it) }.getOrNull() }
                ?: support.tuningProfile
        }

        return when (support.linkMode) {
            LinkMode.CONNECT_GATT ->
                GattScaleAdapter(
                    applicationContext,
                    settingsFacade,
                    measurementFacade,
                    userFacade,
                    handler,
                    effectiveTuning
                )

            LinkMode.BROADCAST_ONLY ->
                BroadcastScaleAdapter(
                    applicationContext,
                    settingsFacade,
                    measurementFacade,
                    userFacade,
                    handler,
                    effectiveTuning
                )

            LinkMode.CLASSIC_SPP ->
                SppScaleAdapter(
                    applicationContext,
                    settingsFacade,
                    measurementFacade,
                    userFacade,
                    handler,
                    effectiveTuning
                )
        }
    }

    /**
     * Creates the most suitable [ScaleCommunicator] for the given scanned device.
     *
     * @param deviceInfo Information about the scanned Bluetooth device.
     * @return A [ScaleCommunicator] instance if a suitable handler or adapter is found, otherwise null.
     */
    fun createCommunicator(deviceInfo: ScannedDeviceInfo): ScaleCommunicator? {
        val primaryIdentifier = deviceInfo.name ?: "UnknownDevice"
        LogManager.d(TAG, "createCommunicator: Searching for communicator for '${primaryIdentifier}' (${deviceInfo.address}). Handler hint: '${deviceInfo.determinedHandlerDisplayName}'")

        // 1. Check if a modern Kotlin handler explicitly supports the device.
        for (handler in modernKotlinHandlers) {
            val support = handler.supportFor(deviceInfo)
            if (support != null) {
                LogManager.i(TAG, "Modern handler '${support.displayName}' supports '$primaryIdentifier'.")
                val modern = createModernCommunicator(handler, support)
                if (modern != null) {
                    LogManager.i(TAG, "Modern communicator '${modern.javaClass.simpleName}' created for '$primaryIdentifier' with linkMode=${support.linkMode}.")
                    return modern
                }
                LogManager.w(TAG, "Modern handler '${support.displayName}' supports '$primaryIdentifier', but no communicator is available.")
            }
        }

        LogManager.w(TAG, "No suitable communicator found for device (name: '${deviceInfo.name}', address: '${deviceInfo.address}', handler hint: '${deviceInfo.determinedHandlerDisplayName}').")
        return null
    }

    fun getDeviceSupportFor(name: String, address: String): DeviceSupport? {
        val info = ScannedDeviceInfo(name, address, 0, emptyList(), null)
        return modernKotlinHandlers.firstNotNullOfOrNull { it.supportFor(info) }
    }

    /**
     * Checks if any known handler can theoretically support the given device.
     * This can be used by the UI to indicate if a device is potentially recognizable.
     *
     * @param deviceInfo Information about the scanned Bluetooth device.
     * @return A Pair where `first` is true if a handler is found, and `second` is the name of the handler/driver, or null.
     */
    fun getSupportingHandlerInfo(deviceInfo : ScannedDeviceInfo): Pair<Boolean, String?> {
        // Check modern handlers first
        for (handler in modernKotlinHandlers) {
            val support = handler.supportFor(deviceInfo)
            if (support != null) return true to support.displayName
        }

        return false to null
    }
}
