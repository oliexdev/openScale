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
import com.health.openscale.core.bluetooth.modern.ScaleDeviceHandler
import com.health.openscale.core.bluetooth.legacy.BluetoothActiveEraBF06
import com.health.openscale.core.bluetooth.legacy.BluetoothBeurerBF105
import com.health.openscale.core.bluetooth.legacy.BluetoothBeurerBF500
import com.health.openscale.core.bluetooth.legacy.BluetoothBeurerBF600
import com.health.openscale.core.bluetooth.legacy.BluetoothBeurerBF950
import com.health.openscale.core.bluetooth.legacy.BluetoothBeurerSanitas
import com.health.openscale.core.bluetooth.legacy.BluetoothBroadcastScale
import com.health.openscale.core.bluetooth.legacy.BluetoothCommunication
import com.health.openscale.core.bluetooth.legacy.BluetoothCustomOpenScale
import com.health.openscale.core.bluetooth.legacy.BluetoothDigooDGSO38H
import com.health.openscale.core.bluetooth.legacy.BluetoothES26BBB
import com.health.openscale.core.bluetooth.legacy.BluetoothESCS20M
import com.health.openscale.core.bluetooth.legacy.BluetoothExcelvanCF36xBLE
import com.health.openscale.core.bluetooth.legacy.BluetoothExingtechY1
import com.health.openscale.core.bluetooth.legacy.BluetoothHesley
import com.health.openscale.core.bluetooth.legacy.BluetoothHoffenBBS8107
import com.health.openscale.core.bluetooth.legacy.BluetoothHuaweiAH100
import com.health.openscale.core.bluetooth.legacy.BluetoothIhealthHS3
import com.health.openscale.core.bluetooth.legacy.BluetoothInlife
import com.health.openscale.core.bluetooth.legacy.BluetoothMGB
import com.health.openscale.core.bluetooth.legacy.BluetoothMedisanaBS44x
import com.health.openscale.core.bluetooth.legacy.BluetoothMiScale
import com.health.openscale.core.bluetooth.legacy.BluetoothMiScale2
import com.health.openscale.core.bluetooth.legacy.BluetoothOKOK
import com.health.openscale.core.bluetooth.legacy.BluetoothOKOK2
import com.health.openscale.core.bluetooth.legacy.BluetoothOneByone
import com.health.openscale.core.bluetooth.legacy.BluetoothOneByoneNew
import com.health.openscale.core.bluetooth.legacy.BluetoothQNScale
import com.health.openscale.core.bluetooth.legacy.BluetoothRenphoScale
import com.health.openscale.core.bluetooth.legacy.BluetoothSanitasSBF72
import com.health.openscale.core.bluetooth.legacy.BluetoothSenssun
import com.health.openscale.core.bluetooth.legacy.BluetoothSinocare
import com.health.openscale.core.bluetooth.legacy.BluetoothSoehnle
import com.health.openscale.core.bluetooth.legacy.BluetoothTrisaBodyAnalyze
import com.health.openscale.core.bluetooth.legacy.BluetoothYoda1Scale
import com.health.openscale.core.bluetooth.legacy.BluetoothYunmaiSE_Mini
import com.health.openscale.core.bluetooth.legacy.LegacyScaleAdapter
import com.health.openscale.core.bluetooth.modern.DeviceSupport
import com.health.openscale.core.bluetooth.modern.ESCS20mHandler
import com.health.openscale.core.bluetooth.modern.InlifeHandler
import com.health.openscale.core.bluetooth.modern.MGBHandler
import com.health.openscale.core.bluetooth.modern.MedisanaBs44xHandler
import com.health.openscale.core.bluetooth.modern.MiScaleHandler
import com.health.openscale.core.bluetooth.modern.ModernScaleAdapter
import com.health.openscale.core.bluetooth.modern.OkOkHandler
import com.health.openscale.core.bluetooth.modern.OneByoneHandler
import com.health.openscale.core.bluetooth.modern.OneByoneNewHandler
import com.health.openscale.core.bluetooth.modern.QNHandler
import com.health.openscale.core.bluetooth.modern.RenphoHandler
import com.health.openscale.core.bluetooth.modern.SanitasSBF72Handler
import com.health.openscale.core.bluetooth.modern.SenssunHandler
import com.health.openscale.core.bluetooth.modern.SinocareHandler
import com.health.openscale.core.bluetooth.modern.SoehnleHandler
import com.health.openscale.core.bluetooth.modern.StandardWeightProfileHandler
import com.health.openscale.core.bluetooth.modern.TrisaBodyAnalyzeHandler
import com.health.openscale.core.bluetooth.modern.Yoda1Handler
import com.health.openscale.core.bluetooth.modern.YunmaiHandler
import com.health.openscale.core.facade.MeasurementFacade
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.facade.UserFacade
import com.health.openscale.core.utils.LogManager
import com.health.openscale.core.service.ScannedDeviceInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Factory class responsible for creating appropriate [ScaleCommunicator] instances
 * for different Bluetooth scale devices. It decides whether to use a modern Kotlin-based
 * handler or a legacy Java-based adapter.
 */
@Singleton
class ScaleFactory @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val settingsFacade: SettingsFacade,
    private val measurementFacade: MeasurementFacade,
    private val userFacade: UserFacade,
    private val legacyAdapterFactory: LegacyScaleAdapter.Factory
) {
    private val TAG = "ScaleHandlerFactory"

    // List of modern Kotlin-based device handlers.
    // These are checked first for device compatibility.
    private val modernKotlinHandlers: List<ScaleDeviceHandler> = listOf(
        YunmaiHandler(isMini = false),
        YunmaiHandler(isMini = true),
        Yoda1Handler(),
        TrisaBodyAnalyzeHandler(),
        SoehnleHandler(),
        SinocareHandler(),
        SenssunHandler(),
        SanitasSBF72Handler(),
        RenphoHandler(),
        ESCS20mHandler(),
        QNHandler(),
        OneByoneHandler(),
        OneByoneNewHandler(),
        OkOkHandler(),
        MiScaleHandler(),
        MGBHandler(),
        MedisanaBs44xHandler(),
        InlifeHandler(),
    )

    /**
     * Attempts to create a legacy Java Bluetooth driver instance based on the device name.
     * This method contains the logic to map device names to specific Java driver classes.
     *
     * @param context The application context.
     * @param deviceInfo Information about the scanned Bluetooth device.
     * @return A [BluetoothCommunication] instance if a matching driver is found, otherwise null.
     */
    private fun createLegacyJavaDriver(context: Context?, deviceInfo: ScannedDeviceInfo): BluetoothCommunication? {
        val deviceName : String
        val name : String

        if (deviceInfo.name != null) {
            deviceName = deviceInfo.name
            name = deviceInfo.name.lowercase()
        } else {
            deviceName = deviceInfo.determinedHandlerDisplayName ?: "UnknownDevice"
            name = deviceInfo.determinedHandlerDisplayName?.lowercase() ?: "UnknownDevice"
        }

        if (name.startsWith("BEURER BF700".lowercase())
            || name.startsWith("BEURER BF800".lowercase())
            || name.startsWith("BF-800".lowercase())
            || name.startsWith("BF-700".lowercase())
            || name.startsWith("RT-Libra-B".lowercase())
            || name.startsWith("RT-Libra-W".lowercase())
            || name.startsWith("Libra-B".lowercase())
            || name.startsWith("Libra-W".lowercase())
        ) {
            return BluetoothBeurerSanitas(
                context,
                BluetoothBeurerSanitas.DeviceType.BEURER_BF700_800_RT_LIBRA
            )
        }
        if (name.startsWith("BEURER BF710".lowercase())
            || name == "BF700".lowercase()
        ) {
            return BluetoothBeurerSanitas(context, BluetoothBeurerSanitas.DeviceType.BEURER_BF710)
        }
        if (name == "openScale".lowercase()) {
            return BluetoothCustomOpenScale(context)
        }
        if (name == "Mengii".lowercase()) {
            return BluetoothDigooDGSO38H(context)
        }
        if (name == "Electronic Scale".lowercase()) {
            return BluetoothExcelvanCF36xBLE(context)
        }
        if (name == "VScale".lowercase()) {
            return BluetoothExingtechY1(context)
        }
        if (name == "YunChen".lowercase()) {
            return BluetoothHesley(context)
        }
        if (deviceName.startsWith("iHealth HS3")) {
            return BluetoothIhealthHS3(context)
        }

        // BS444 || BS440
        if (deviceName.startsWith("013197") || deviceName.startsWith("013198") || deviceName.startsWith(
                "0202B6"
            )
        ) {
            return BluetoothMedisanaBS44x(context, true)
        }

        //BS430
        if (deviceName.startsWith("0203B")) {
            return BluetoothMedisanaBS44x(context, false)
        }

        if (deviceName.startsWith("SWAN") || name == "icomon".lowercase() || name == "YG".lowercase()) {
            return BluetoothMGB(context)
        }
        if (name == "MI_SCALE".lowercase() || name == "MI SCALE2".lowercase()) {
            return BluetoothMiScale(context)
        }
        if (name == "MIBCS".lowercase() || name == "MIBFS".lowercase()) {
            return BluetoothMiScale2(context)
        }
        if (name == "Health Scale".lowercase()) {
            return BluetoothOneByone(context)
        }
        if (name == "1byone scale".lowercase()) {
            return BluetoothOneByoneNew(context)
        }

        if (name == "SENSSUN FAT".lowercase()) {
            return BluetoothSenssun(context)
        }
        if (name.startsWith("SANITAS SBF70".lowercase()) || name.startsWith("sbf75") || name.startsWith(
                "AICDSCALE1".lowercase()
            )
        ) {
            return BluetoothBeurerSanitas(
                context,
                BluetoothBeurerSanitas.DeviceType.SANITAS_SBF70_70
            )
        }
        if (deviceName.startsWith("YUNMAI-SIGNAL") || deviceName.startsWith("YUNMAI-ISM")) {
            return BluetoothYunmaiSE_Mini(context, true)
        }
        if (deviceName.startsWith("YUNMAI-ISSE")) {
            return BluetoothYunmaiSE_Mini(context, false)
        }
        if (deviceName.startsWith("01257B") || deviceName.startsWith("11257B")) {
            // Trisa Body Analyze 4.0, aka Transtek GBF-1257-B
            return BluetoothTrisaBodyAnalyze(context)
        }
        if (deviceName == "000FatScale01" || deviceName == "000FatScale02"
            || deviceName == "042FatScale01"
        ) {
            return BluetoothInlife(context)
        }
        if (deviceName.startsWith("QN-Scale")) {
            return BluetoothQNScale(context)
        }
        if (deviceName.startsWith("Shape200") || deviceName.startsWith("Shape100") || deviceName.startsWith(
                "Shape50"
            ) || deviceName.startsWith("Style100")
        ) {
            return BluetoothSoehnle(context)
        }
        if (deviceName == "Hoffen BS-8107") {
            return BluetoothHoffenBBS8107(context)
        }
        if (deviceName == "ADV" || deviceName == "Chipsea-BLE") {
            return BluetoothOKOK(context)
        }
        if (deviceName == "NoName OkOk") {
            return BluetoothOKOK2(context)
        }
        if (deviceName == "BF105" || deviceName == "BF720") {
            return BluetoothBeurerBF105(context)
        }
        if (deviceName == "BF500") {
            return BluetoothBeurerBF500(context, deviceName)
        }
        if (deviceName == "BF600" || deviceName == "BF850") {
            return BluetoothBeurerBF600(context, deviceName)
        }
        if (deviceName == "SBF77" || deviceName == "SBF76" || deviceName == "BF950") {
            return BluetoothBeurerBF950(context, deviceName)
        }
        if (deviceName == "SBF72" || deviceName == "BF915" || deviceName == "SBF73") {
            return BluetoothSanitasSBF72(context, deviceName)
        }
        if (deviceName == "Weight Scale") {
            return BluetoothSinocare(context)
        }
        if (deviceName == "CH100") {
            return BluetoothHuaweiAH100(context)
        }
        if (deviceName == "ES-26BB-B") {
            return BluetoothES26BBB(context)
        }
        if (deviceName == "Yoda1") {
            return BluetoothYoda1Scale(context)
        }
        if (deviceName == "AAA002" || deviceName == "AAA007" || deviceName == "AAA013") {
            return BluetoothBroadcastScale(context)
        }
        if (deviceName == "AE BS-06") {
            return BluetoothActiveEraBF06(context)
        }
        if (deviceName == "Renpho-Scale") {
            /* Driver for Renpho ES-WBE28, which has device name of "Renpho-Scale".
               "Renpho-Scale" is quite generic, not sure if other Renpho scales with different
               protocol match this name.
             */
            return BluetoothRenphoScale(context)
        }
        if (deviceName == "ES-CS20M") {
            return BluetoothESCS20M(context)
        }

        return null
    }

    /**
     * Creates a [ScaleCommunicator] using the legacy Java driver approach.
     * It wraps a [BluetoothCommunication] instance (Java driver) in a [LegacyScaleAdapter].
     *
     * @param deviceInfo The device information used to find a legacy Java driver.
     * @return A [LegacyScaleAdapter] instance if a suitable Java driver is found, otherwise null.
     */
    private fun createLegacyCommunicator(deviceInfo: ScannedDeviceInfo): ScaleCommunicator? {
        val javaDriverInstance = createLegacyJavaDriver(applicationContext, deviceInfo)
        return if (javaDriverInstance != null) {
            LogManager.i(TAG, "Creating LegacyScaleAdapter via Hilt factory for '${javaDriverInstance.javaClass.simpleName}'.")
            legacyAdapterFactory.create(javaDriverInstance)
        } else {
            LogManager.w(TAG, "Could not create LegacyScaleAdapter: No Java driver found for '${deviceInfo.name}'.")
            null
        }
    }

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
        LogManager.i(TAG, "Creating ModernScaleAdapter for handler '${handler.javaClass.simpleName}'.")
        return ModernScaleAdapter(
            context = applicationContext,
            settingsFacade = settingsFacade,
            measurementFacade = measurementFacade,
            userFacade = userFacade,
            handler = handler,
            bleTuning = support.bleTuning
        )
    }

    /**
     * Creates the most suitable [ScaleCommunicator] for the given scanned device.
     * It prioritizes modern Kotlin-based handlers and falls back to legacy adapters if necessary.
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
                    LogManager.i(TAG, "Modern communicator '${modern.javaClass.simpleName}' created for '$primaryIdentifier'.")
                    return modern
                }
                LogManager.w(TAG, "Modern handler '${support.displayName}' supports '$primaryIdentifier', but no communicator is available.")
            }
        }
        LogManager.d(TAG, "No modern Kotlin handler actively supported '$primaryIdentifier' or could create a communicator.")

        // 2. Fallback to legacy adapter if no modern handler matched or created a communicator.
        LogManager.i(TAG, "Attempting fallback to legacy adapter for identifier '${deviceInfo.name}'.")
        val legacyCommunicator = createLegacyCommunicator(deviceInfo)
        if (legacyCommunicator != null) {
            LogManager.i(TAG, "Legacy communicator '${legacyCommunicator.javaClass.simpleName}' created for device ('${deviceInfo.name}').")
            return legacyCommunicator
        }

        LogManager.w(TAG, "No suitable communicator (neither modern nor legacy) found for device (name: '${deviceInfo.name}', address: '${deviceInfo.address}', handler hint: '${deviceInfo.determinedHandlerDisplayName}').")
        return null
    }

    /**
     * Checks if any known handler (modern Kotlin or legacy Java-based) can theoretically support the given device.
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

        // Then check if a legacy driver would exist based on the name
        if (deviceInfo.name != null) {
            val legacyJavaDriver = createLegacyJavaDriver(applicationContext, deviceInfo)
            if (legacyJavaDriver != null) {
                // Return the driver name from the BluetoothCommunication interface if available and meaningful.
                return true to legacyJavaDriver.driverName() // Assumes BluetoothCommunication has a driverName() method.
            }
        }
        LogManager.d(TAG, "getSupportingHandlerInfo: No supporting handler found for ${deviceInfo.name}.")
        return false to null
    }
}
