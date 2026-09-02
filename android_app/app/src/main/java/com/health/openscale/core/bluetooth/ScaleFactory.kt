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
import androidx.annotation.VisibleForTesting
import com.health.openscale.core.bluetooth.scales.HealthKeep280Handler
import com.health.openscale.core.bluetooth.scales.BeurerBF450Handler
import com.health.openscale.core.bluetooth.scales.ScaleDeviceHandler
import com.health.openscale.core.bluetooth.scales.AAAxHandler
import com.health.openscale.core.bluetooth.scales.AfuB1Handler
import com.health.openscale.core.bluetooth.scales.AiLinkBroadcastHandler
import com.health.openscale.core.bluetooth.scales.FitTrackDaraHandler
import com.health.openscale.core.bluetooth.scales.ScaleupHandler
import com.health.openscale.core.bluetooth.scales.ActiveEraBF06Handler
import com.health.openscale.core.bluetooth.scales.AndUC352BLEHandler
import com.health.openscale.core.bluetooth.scales.CultSmartScaleProHandler
import com.health.openscale.core.bluetooth.scales.BeurerSanitasHandler
import com.health.openscale.core.bluetooth.scales.BroadcastScaleAdapter
import com.health.openscale.core.bluetooth.scales.CustomOpenScaleHandler
import com.health.openscale.core.bluetooth.scales.DebugGattHandler
import com.health.openscale.core.bluetooth.scales.DeviceSupport
import com.health.openscale.core.bluetooth.scales.DigooDGSO38HHandler
import com.health.openscale.core.bluetooth.scales.ESCS20MHandler
import com.health.openscale.core.bluetooth.scales.ExcelvanCF36xHandler
import com.health.openscale.core.bluetooth.scales.ExingtechY1Handler
import com.health.openscale.core.bluetooth.scales.EufyC20Handler
import com.health.openscale.core.bluetooth.scales.EufyP2Handler
import com.health.openscale.core.bluetooth.scales.EbelterBodyFatB2Handler
import com.health.openscale.core.bluetooth.scales.EtekcityESF551Handler
import com.health.openscale.core.bluetooth.scales.EtekcityFit8SHandler
import com.health.openscale.core.bluetooth.scales.GattScaleAdapter
import com.health.openscale.core.bluetooth.scales.HesleyHandler
import com.health.openscale.core.bluetooth.scales.HoffenBbs8107Handler
import com.health.openscale.core.bluetooth.scales.HuaweiAhCh100Handler
import com.health.openscale.core.bluetooth.scales.HuaweiCH100SHandler
import com.health.openscale.core.bluetooth.scales.HuaweiHagridWspHandler
import com.health.openscale.core.bluetooth.scales.HumeDara2Handler
import com.health.openscale.core.bluetooth.scales.IHealthHS3Handler
import com.health.openscale.core.bluetooth.scales.InlifeHandler
import com.health.openscale.core.bluetooth.scales.KeepS3Handler
import com.health.openscale.core.bluetooth.scales.LinkMode
import com.health.openscale.core.bluetooth.scales.MGBHandler
import com.health.openscale.core.bluetooth.scales.MedisanaBs44xHandler
import com.health.openscale.core.bluetooth.scales.MiScaleHandler
import com.health.openscale.core.bluetooth.scales.MiScaleS400Handler
import com.health.openscale.core.bluetooth.scales.XiaomiS800Handler
import com.health.openscale.core.bluetooth.scales.BodyConnectHandler
import com.health.openscale.core.bluetooth.scales.OkOkHandler
import com.health.openscale.core.bluetooth.scales.OmronWlcHandler
import com.health.openscale.core.bluetooth.scales.PicoocHandler
import com.health.openscale.core.bluetooth.scales.OneByoneHandler
import com.health.openscale.core.bluetooth.scales.OneByoneNewHandler
import com.health.openscale.core.bluetooth.scales.QNHandler
import com.health.openscale.core.bluetooth.scales.QNHandlerBroadcast
import com.health.openscale.core.bluetooth.scales.RealmeSmartScaleHandler
import com.health.openscale.core.bluetooth.scales.RenphoES26BBHandler
import com.health.openscale.core.bluetooth.scales.RenphoHandler
import com.health.openscale.core.bluetooth.scales.RelaxmedicHandler
import com.health.openscale.core.bluetooth.scales.RobiS9Handler
import com.health.openscale.core.bluetooth.scales.RunstarR5Handler
import com.health.openscale.core.bluetooth.scales.RunstarR6Handler
import com.health.openscale.core.bluetooth.scales.RyFitHandler
import com.health.openscale.core.bluetooth.scales.SanitasSbf72Handler
import com.health.openscale.core.bluetooth.scales.SenssunHandler
import com.health.openscale.core.bluetooth.scales.SinocareHandler
import com.health.openscale.core.bluetooth.scales.SoehnleHandler
import com.health.openscale.core.bluetooth.scales.SppScaleAdapter
import com.health.openscale.core.bluetooth.scales.TaylorBIAHandler
import com.health.openscale.core.bluetooth.scales.DrTrustSSW532Handler
import com.health.openscale.core.bluetooth.scales.EEBBLHandler
import com.health.openscale.core.bluetooth.scales.StandardBeurerSanitasHandler
import com.health.openscale.core.bluetooth.scales.TrisaBodyAnalyzeHandler
import com.health.openscale.core.bluetooth.scales.TuningProfile
import com.health.openscale.core.bluetooth.scales.VitafitVT701Handler
import com.health.openscale.core.bluetooth.scales.WeightGurusA3Handler
import com.health.openscale.core.bluetooth.scales.YunmaiHandler
import com.health.openscale.core.bluetooth.scales.YunmaiXHandler
import com.health.openscale.core.facade.MeasurementFacade
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.facade.UserFacade
import com.health.openscale.core.utils.LogManager
import com.health.openscale.core.service.ScannedDeviceInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Factory class responsible for creating appropriate [ScaleCommunicator] instances
 * for different Bluetooth scale devices.
 */
@Singleton
class ScaleFactory @Inject constructor(
    @param:ApplicationContext private val applicationContext: Context,
    private val settingsFacade: SettingsFacade,
    private val measurementFacade: MeasurementFacade,
    private val userFacade: UserFacade,
) {
    private val TAG = "ScaleHandlerFactory"

    private val modernKotlinHandlers: List<ScaleDeviceHandler> = createHandlers()

    companion object {
        /**
         * Builds the list of modern Kotlin-based device handlers.
         *
         * Order matters: [createCommunicator] returns the FIRST handler whose [ScaleDeviceHandler.supportFor]
         * is non-null. TaylorBIAHandler, FitTrackDaraHandler, RelaxmedicHandler, RobiS9Handler and
         * DrTrustSSW532Handler must stay ahead of MGBHandler — all live on service 0xFFB0, which
         * MGBHandler matches on its own, so a later position would let MGB wrongly claim them.
         *
         * Exposed so the registry (order, device claims, duplicates) can be asserted in unit tests
         * without building the Hilt graph — see `ScaleFactoryTest`.
         */
        @VisibleForTesting
        internal fun createHandlers(): List<ScaleDeviceHandler> = listOf(
            AndUC352BLEHandler(),
            // Exact-name match must precede generic LeFu/0xFFF0 handlers (first match wins).
            HealthKeep280Handler(),
            PicoocHandler(),
            ActiveEraBF06Handler(),
            AfuB1Handler(),
            KeepS3Handler(),
            OmronWlcHandler(),
            BeurerBF450Handler(),
            TaylorBIAHandler(),
            RyFitHandler(),
            CultSmartScaleProHandler(),
            RealmeSmartScaleHandler(),
            YunmaiHandler(isMini = false),
            YunmaiHandler(isMini = true),
            YunmaiXHandler(),
            TrisaBodyAnalyzeHandler(),
            SanitasSbf72Handler(),
            StandardBeurerSanitasHandler(),
            SoehnleHandler(),
            SinocareHandler(),
            SenssunHandler(),
            RenphoHandler(),
            AiLinkBroadcastHandler(),
            QNHandlerBroadcast(),
            QNHandler(),
            OneByoneHandler(),
            OneByoneNewHandler(),
            OkOkHandler(),
            MiScaleS400Handler(),
            XiaomiS800Handler(),
            MiScaleHandler(),
            RunstarR6Handler(),
            RunstarR5Handler(),
            RelaxmedicHandler(),
            RobiS9Handler(),
            VitafitVT701Handler(),
            EEBBLHandler(),
            FitTrackDaraHandler(),
            DrTrustSSW532Handler(),
            MGBHandler(),
            MedisanaBs44xHandler(),
            InlifeHandler(),
            IHealthHS3Handler(),
            HuaweiAhCh100Handler(),
            HuaweiCH100SHandler(),
            HuaweiHagridWspHandler(),
            HoffenBbs8107Handler(),
            HesleyHandler(),
            ExingtechY1Handler(),
            EbelterBodyFatB2Handler(),
            HumeDara2Handler(),
            ExcelvanCF36xHandler(),
            EtekcityESF551Handler(),
            EtekcityFit8SHandler(),
            EufyC20Handler(),
            EufyP2Handler(),
            ESCS20MHandler(),
            RenphoES26BBHandler(),
            DigooDGSO38HHandler(),
            CustomOpenScaleHandler(),
            BeurerSanitasHandler(),
            AAAxHandler(),
            ScaleupHandler(),
            BodyConnectHandler(),
            WeightGurusA3Handler(),
        )
    }

    /**
     * Reads the current value of a settings [Flow] from a non-suspending context.
     *
     * Communicator creation happens on the caller's thread, so the few settings needed here are
     * read with a short timeout rather than restructuring every call site; `null` means the value
     * was unavailable in time and the caller falls back to its default.
     */
    private fun <T> readSettingBlocking(flow: Flow<T>): T? = runCatching {
        runBlocking(Dispatchers.IO) {
            withTimeout(250.milliseconds) { flow.firstOrNull() }
        }
    }.getOrNull()

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
            val saved: String? = readSettingBlocking(settingsFacade.savedBluetoothTuneProfile)

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
        val primaryIdentifier = deviceInfo.name
        LogManager.d(TAG, "createCommunicator: Searching for communicator for '${primaryIdentifier}' (${deviceInfo.address}). Handler hint: '${deviceInfo.determinedHandlerDisplayName}'")

        // 0. Developer mode wins over every registered handler: route to the diagnostic handler,
        //    which dumps the GATT tree and logs notifications but stores nothing.
        if (readSettingBlocking(settingsFacade.developerModeEnabled) == true) {
            LogManager.i(TAG, "Developer mode active → routing '$primaryIdentifier' to DebugGattHandler. No measurement will be stored.")
            return createModernCommunicator(DebugGattHandler(), DebugGattHandler.SUPPORT)
        }

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

    /**
     * Returns the [DeviceSupport] of the first handler that claims [device].
     *
     * Always pass the complete advertisement: handlers that identify a scale by its services,
     * manufacturer data or service data (Etekcity Fit 8S, Yunmai X, the standard weight profile,
     * ...) cannot recognise it from name and address alone and would report "no support".
     */
    fun getDeviceSupportFor(device: ScannedDeviceInfo): DeviceSupport? =
        modernKotlinHandlers.firstNotNullOfOrNull { it.supportFor(device) }

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
