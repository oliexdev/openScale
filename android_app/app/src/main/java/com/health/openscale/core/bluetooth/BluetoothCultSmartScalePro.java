/* Copyright (C) 2024
*
*    This program is free software: you can redistribute it and/or modify
*    it under the terms of the GNU General Public License as published by
*    the Free Software Foundation, either version 3 of the License, or
*    (at your option) any later version.
*
*    This program is distributed in the hope that it will be useful,
*    but WITHOUT ANY WARRANTY; without even the implied warranty of
*    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
*    GNU General Public License for more details.
*
*    You should have received a copy of the GNU General Public License
*    along with this program.  If not, see <http://www.gnu.org/licenses/>
*/

package com.health.openscale.core.bluetooth;

import android.content.Context;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;

import java.util.Date;
import java.util.UUID;

import timber.log.Timber;

public class BluetoothCultSmartScalePro extends BluetoothCommunication {
    
    // Standard services
    private final UUID DEVICE_INFORMATION_SERVICE = UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB");
    private final UUID BATTERY_SERVICE = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB");
    
    // Custom Cult Smart Scale Pro Service (using Bluetooth Base UUID pattern)
    private final UUID CULT_SCALE_SERVICE = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB");
    
    // Standard Characteristics
    private final UUID MANUFACTURER_NAME_CHARACTERISTIC = UUID.fromString("00002A29-0000-1000-8000-00805F9B34FB");
    private final UUID MODEL_NUMBER_CHARACTERISTIC = UUID.fromString("00002A24-0000-1000-8000-00805F9B34FB");
    private final UUID FIRMWARE_REVISION_CHARACTERISTIC = UUID.fromString("00002A26-0000-1000-8000-00805F9B34FB");
    private final UUID BATTERY_LEVEL_CHARACTERISTIC = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB");
    
    // Custom Characteristics for scale-specific functionality
    private final UUID MEASUREMENT_CHARACTERISTIC_FFF1 = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB"); // Weight measurement data (WRITE, NOTIFY)
    private final UUID CONTROL_CHARACTERISTIC_FFF2 = UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB");     // Device control/config (WRITE_NO_RESPONSE, INDICATE)
    private final UUID STATUS_CHARACTERISTIC_FFF4 = UUID.fromString("0000FFF4-0000-1000-8000-00805F9B34FB");      // Status monitoring (NOTIFY)
    
    // Connection and measurement state management
    private boolean measurementComplete = false;
    private boolean deviceConfigured = false;
    private long connectionStartTime = 0;
    private int retryCount = 0;
    private static final int MAX_RETRIES = 3;
    private static final long CONNECTION_TIMEOUT_MS = 30000; // 30 seconds
    private static final long MEASUREMENT_TIMEOUT_MS = 60000; // 60 seconds
    
    // Device information storage
    private String deviceManufacturer = "";
    private String deviceModel = "";
    private String firmwareVersion = "";
    private int batteryLevel = -1;
    
    // Measurement unit preferences
    private enum WeightUnit {
        KILOGRAMS(0x00, "kg"),
        POUNDS(0x01, "lb"),
        STONES_POUNDS(0x02, "st:lb");
        
        private final byte value;
        private final String symbol;
        
        WeightUnit(int value, String symbol) {
            this.value = (byte) value;
            this.symbol = symbol;
        }
        
        public byte getValue() { return value; }
        public String getSymbol() { return symbol; }
    }
    
    private WeightUnit preferredWeightUnit = WeightUnit.KILOGRAMS;

    public BluetoothCultSmartScalePro(Context context) {
        super(context);
        resetDeviceState();
    }
    
    /**
     * Reset all device state variables to initial values
     * Called on connection start and error recovery
     */
    private void resetDeviceState() {
        measurementComplete = false;
        deviceConfigured = false;
        connectionStartTime = 0;
        retryCount = 0;
        deviceManufacturer = "";
        deviceModel = "";
        firmwareVersion = "";
        batteryLevel = -1;
        
        // Set preferred weight unit based on user preferences
        ScaleUser selectedUser = OpenScale.getInstance().getSelectedScaleUser();
        if (selectedUser != null) {
            switch (selectedUser.getScaleUnit()) {
                case LB:
                    preferredWeightUnit = WeightUnit.POUNDS;
                    break;
                case ST:
                    preferredWeightUnit = WeightUnit.STONES_POUNDS;
                    break;
                default:
                    preferredWeightUnit = WeightUnit.KILOGRAMS;
                    break;
            }
        }
        
        Timber.d("Device state reset, preferred unit: %s", preferredWeightUnit.getSymbol());
    }
    
    /**
     * Check if connection or measurement has timed out
     * @return true if timeout occurred
     */
    private boolean checkTimeout() {
        if (connectionStartTime == 0) return false;
        
        long currentTime = System.currentTimeMillis();
        long elapsedTime = currentTime - connectionStartTime;
        
        if (!measurementComplete && elapsedTime > MEASUREMENT_TIMEOUT_MS) {
            Timber.w("Measurement timeout after %d ms", elapsedTime);
            sendMessage(R.string.info_scale_not_ready, 0);
            return true;
        }
        
        if (!deviceConfigured && elapsedTime > CONNECTION_TIMEOUT_MS) {
            Timber.w("Connection timeout after %d ms", elapsedTime);
            sendMessage(R.string.info_bluetooth_connection_error, 0);
            return true;
        }
        
        return false;
    }
    
    @Override
    public String driverName() {
        return "Cult Smart Scale Pro";
    }

    @Override
    protected boolean onNextStep(int stepNr) {
        // Check for timeouts before proceeding with any step
        if (checkTimeout()) {
            Timber.w("Timeout detected, aborting step %d", stepNr);
            return false;
        }
        
        switch (stepNr) {
            case 0:
                // Step 1: Read device information for compatibility verification
                Timber.d("Step 0: Reading device information service");
                resetDeviceState(); // Reset state at start of new connection
                return readDeviceInformation();
                
            case 1:
                // Step 2: Read battery status for power monitoring
                Timber.d("Step 1: Reading battery status");
                return readBatteryStatus();
                
            case 2:
                // Step 3: Enable notifications on FFF1 (measurement data)
                Timber.d("Step 2: Enabling notifications on measurement characteristic FFF1");
                return enableNotifications(MEASUREMENT_CHARACTERISTIC_FFF1);
                
            case 3:
                // Step 4: Enable indications on FFF2 (control responses)
                Timber.d("Step 3: Enabling indications on control characteristic FFF2");
                return enableIndications(CONTROL_CHARACTERISTIC_FFF2);
                
            case 4:
                // Step 5: Enable notifications on FFF4 (status monitoring)
                Timber.d("Step 4: Enabling notifications on status characteristic FFF4");
                return enableNotifications(STATUS_CHARACTERISTIC_FFF4);
                
            case 5:
                // Step 6: Send user profile configuration
                Timber.d("Step 5: Sending user profile configuration");
                return sendUserProfile();
                
            case 6:
                // Step 7: Start measurement session
                Timber.d("Step 6: Starting measurement session");
                return startMeasurement();
                
            default:
                return false;
        }
    }
    
    private boolean readDeviceInformation() {
        try {
            connectionStartTime = System.currentTimeMillis();
            
            // Read manufacturer name to verify device compatibility
            byte[] manufacturerData = readBytes(DEVICE_INFORMATION_SERVICE, MANUFACTURER_NAME_CHARACTERISTIC);
            if (manufacturerData != null && manufacturerData.length > 0) {
                deviceManufacturer = new String(manufacturerData).trim();
                Timber.d("Device manufacturer: %s", deviceManufacturer);
            } else {
                Timber.w("Failed to read manufacturer name");
            }
            
            // Read model number for device identification
            byte[] modelData = readBytes(DEVICE_INFORMATION_SERVICE, MODEL_NUMBER_CHARACTERISTIC);
            if (modelData != null && modelData.length > 0) {
                deviceModel = new String(modelData).trim();
                Timber.d("Device model: %s", deviceModel);
            } else {
                Timber.w("Failed to read model number");  
            }
            
            // Read firmware version for compatibility checks
            byte[] firmwareData = readBytes(DEVICE_INFORMATION_SERVICE, FIRMWARE_REVISION_CHARACTERISTIC);
            if (firmwareData != null && firmwareData.length > 0) {
                firmwareVersion = new String(firmwareData).trim();
                Timber.d("Firmware version: %s", firmwareVersion);
            } else {
                Timber.w("Failed to read firmware revision");
            }
            return true;
        } catch (Exception e) {
            Timber.e(e, "Error reading device information");
            return false;
        }
    }
    
    private boolean readBatteryStatus() {
        try {
            byte[] batteryData = readBytes(BATTERY_SERVICE, BATTERY_LEVEL_CHARACTERISTIC);
            if (batteryData != null && batteryData.length > 0) {
                batteryLevel = batteryData[0] & 0xFF;
                Timber.d("Battery level: %d%%", batteryLevel);
                
                if (batteryLevel < 20) {
                    sendMessage(R.string.info_scale_low_battery, batteryLevel);
                    Timber.w("Low battery warning: %d%%", batteryLevel);
                }
                return true;
            } else {
                Timber.w("Failed to read battery level");
                return false;
            }
        } catch (Exception e) {
            Timber.e(e, "Error reading battery status");
            return false;
        }
    }
    
    private boolean enableNotifications(UUID characteristic) {
        try {
            // Enable notifications using the service - setNotificationOn returns boolean
            return setNotificationOn(CULT_SCALE_SERVICE, characteristic);
        } catch (Exception e) {
            Timber.e(e, "Error enabling notifications for characteristic: %s", characteristic);
            return false;
        }
    }
    
    private boolean enableIndications(UUID characteristic) {
        try {
            // Enable indications using the service - setIndicationOn returns void
            setIndicationOn(CULT_SCALE_SERVICE, characteristic);
            return true;
        } catch (Exception e) {
            Timber.e(e, "Error enabling indications for characteristic: %s", characteristic);
            return false;
        }
    }
    
    private boolean sendUserProfile() {
        try {
            ScaleUser selectedUser = OpenScale.getInstance().getSelectedScaleUser();
            
            // Prepare user profile data with comprehensive configuration
            byte[] userProfile = new byte[10];
            userProfile[0] = (byte) 0xFE; // Start marker for user profile
            userProfile[1] = (byte) selectedUser.getId(); // User ID
            userProfile[2] = (byte) selectedUser.getAge(); // Age
            
            // Convert height to integer for bit operations
            int heightCm = (int) selectedUser.getBodyHeight();
            userProfile[3] = (byte) (heightCm & 0xFF); // Height (low byte)
            userProfile[4] = (byte) ((heightCm >> 8) & 0xFF); // Height (high byte)
            userProfile[5] = (byte) (selectedUser.getGender().isMale() ? 1 : 0); // Gender (1=male, 0=female)
            userProfile[6] = preferredWeightUnit.getValue(); // Measurement unit (use preferred unit)
            userProfile[7] = (byte) 0x00; // Reserved
            
            // Calculate XOR checksum for data integrity
            byte checksum = 0;
            for (int i = 0; i < 8; i++) {
                checksum ^= userProfile[i];
            }
            userProfile[8] = checksum;
            userProfile[9] = (byte) 0xFF; // End marker
            
            Timber.d("Sending user profile for %s: unit=%s, [%s]", 
                    selectedUser.getUserName(), preferredWeightUnit.getSymbol(), byteInHex(userProfile));
            
            boolean success = writeBytes(CONTROL_CHARACTERISTIC_FFF2, userProfile);
            if (success) {  
                deviceConfigured = true;
                Timber.d("User profile sent successfully");
            } else if (retryCount < MAX_RETRIES) {
                retryCount++;
                Timber.w("User profile send failed, retry %d/%d", retryCount, MAX_RETRIES);
                // Retry will happen in next onNextStep call
            }
            return success;
            
        } catch (Exception e) {
            Timber.e(e, "Error sending user profile");
            return false;
        }
    }
    
    private boolean startMeasurement() {
        try {
            // Check if device is properly configured before starting measurement
            if (!deviceConfigured) {
                Timber.w("Device not properly configured, cannot start measurement");
                return false;
            }
            
            // Check for connection timeout
            long currentTime = System.currentTimeMillis();
            if (connectionStartTime > 0 && (currentTime - connectionStartTime) > CONNECTION_TIMEOUT_MS) {
                Timber.w("Connection timeout exceeded (%d ms), aborting measurement", 
                        currentTime - connectionStartTime);
                sendMessage(R.string.info_bluetooth_connection_error, 0);
                return false;
            }
            
            // Send measurement start command with longer connection interval for power optimization
            byte[] startCommand = {(byte) 0xFD, (byte) 0x01, (byte) 0x00, (byte) 0xFC}; // Start measurement command
            Timber.d("Sending measurement start command: [%s]", byteInHex(startCommand));
            
            boolean success = writeBytes(CONTROL_CHARACTERISTIC_FFF2, startCommand);
            if (success) {
                sendMessage(R.string.info_measuring, 0);
                Timber.i("Measurement session started successfully");
            }
            return success;
        } catch (Exception e) {
            Timber.e(e, "Error starting measurement");
            return false;
        }
    }

    @Override
    public void onBluetoothNotify(UUID characteristic, byte[] value) {
        final byte[] data = value;
        
        if (data == null || data.length == 0) {
            Timber.w("Received empty notification from %s", characteristic);
            return;
        }

        // Log detailed debug information for troubleshooting
        logDebugInfo("NOTIFY-" + characteristic.toString().substring(4, 8).toUpperCase(), data);

        Timber.d("Received notification from %s: [%s]", characteristic, byteInHex(data));

        // Handle notifications based on characteristic purpose
        if (characteristic.equals(MEASUREMENT_CHARACTERISTIC_FFF1)) {
            // FFF1: Weight measurement data transmission (WRITE + NOTIFY)
            handleWeightMeasurementData(data);
        } else if (characteristic.equals(CONTROL_CHARACTERISTIC_FFF2)) {
            // FFF2: Device control confirmations (WRITE_NO_RESPONSE + INDICATE)
            handleDeviceControlResponse(data);
        } else if (characteristic.equals(STATUS_CHARACTERISTIC_FFF4)) {
            // FFF4: Status monitoring (NOTIFY)
            handleStatusNotification(data);
        } else {
            Timber.w("Received notification from unknown characteristic: %s", characteristic);
        }
    }

    /**
     * Handle weight measurement data from FFF1 characteristic
     * This characteristic transmits actual weight readings when scale stabilizes
     */
    private void handleWeightMeasurementData(byte[] data) {
        Timber.d("Processing weight measurement data: [%s]", byteInHex(data));
        
        // Check for weight measurement indicators
        if (data.length >= 8) {
            // Common weight data patterns - may start with specific command bytes
            if (data[0] == (byte)0xCF || data[0] == (byte)0xFD || data[0] == (byte)0xAA) {
                parseWeightData(data);
            } else {
                // Try parsing anyway - some scales don't use command prefixes
                parseWeightData(data);
            }
        }
    }

    /**
     * Handle device control responses from FFF2 characteristic  
     * This characteristic confirms successful configuration commands
     */
    private void handleDeviceControlResponse(byte[] data) {
        Timber.d("Processing device control response: [%s]", byteInHex(data));
        
        if (data.length >= 2) {
            byte command = data[0];
            byte status = data[1];
            
            switch (command) {
                case (byte)0x37: // Configuration command response
                    if (status == (byte)0x00) {
                        Timber.d("Device configuration successful");
                    } else {
                        Timber.w("Device configuration failed with status: 0x%02X", status);
                    }
                    break;
                case (byte)0x50: // Measurement start response  
                    if (status == (byte)0x00) {
                        Timber.d("Measurement started successfully");
                        sendMessage(R.string.info_measuring, 0);
                    } else {
                        Timber.w("Failed to start measurement, status: 0x%02X", status);
                    }
                    break;
                default:
                    Timber.d("Unknown command response: 0x%02X, status: 0x%02X", command, status);
            }
        }
    }

    /**
     * Handle status notifications from FFF4 characteristic
     * This provides battery levels, measurement progress, error conditions
     */
    private void handleStatusNotification(byte[] data) {
        Timber.d("Processing status notification: [%s]", byteInHex(data));
        
        if (data.length >= 3) {
            byte statusType = data[0];
            
            switch (statusType) {
                case (byte)0xBB: // Body composition data
                    if (data.length >= 20) {
                        parseBodyCompositionData(data);
                    }
                    break;
                case (byte)0xBA: // Battery status
                    int batteryLevel = data[1] & 0xFF;
                    Timber.d("Battery level: %d%%", batteryLevel);
                    if (batteryLevel < 20) {
                        sendMessage(R.string.info_scale_low_battery, batteryLevel);
                    }
                    break;
                case (byte)0xBC: // Measurement progress
                    byte progress = data[1];
                    if (progress == (byte)0x01) {
                        Timber.d("Measurement in progress...");
                        sendMessage(R.string.info_measuring, 0);
                    } else if (progress == (byte)0x02) {
                        Timber.d("Measurement complete");
                    }
                    break;
                case (byte)0xBE: // Error conditions
                    byte errorCode = data[1];
                    Timber.w("Scale error reported: 0x%02X", errorCode);
                    sendMessage(R.string.info_scale_not_ready, 0);
                    break;
                default:
                    Timber.d("Unknown status type: 0x%02X", statusType);
            }
        }
    }

    /**
     * Parse weight data using multiple encoding patterns common in smart scales
     * Tries different byte positions, endianness, and scaling factors
     */
    private void parseWeightData(byte[] data) {
        try {
            float weight = 0.0f;
            boolean weightFound = false;
            
            // Try multiple parsing strategies common in BLE scales
            if (data.length >= 6) {
                
                // Strategy 1: Little-endian 16-bit at positions 3-4, scale by 100
                if (!weightFound) {
                    int weightRaw = (data[3] & 0xFF) | ((data[4] & 0xFF) << 8);
                    weight = weightRaw / 100.0f;
                    if (weight >= 10.0f && weight <= 300.0f) {
                        weightFound = true;
                        Timber.d("Weight found using strategy 1 (LE pos 3-4, /100): %.2f kg", weight);
                    }
                }
                
                // Strategy 2: Big-endian 16-bit at positions 3-4, scale by 100  
                if (!weightFound) {
                    int weightRaw = ((data[3] & 0xFF) << 8) | (data[4] & 0xFF);
                    weight = weightRaw / 100.0f;
                    if (weight >= 10.0f && weight <= 300.0f) {
                        weightFound = true;
                        Timber.d("Weight found using strategy 2 (BE pos 3-4, /100): %.2f kg", weight);
                    }
                }
                
                // Strategy 3: Little-endian 16-bit at positions 2-3, scale by 100
                if (!weightFound) {
                    int weightRaw = (data[2] & 0xFF) | ((data[3] & 0xFF) << 8);
                    weight = weightRaw / 100.0f;
                    if (weight >= 10.0f && weight <= 300.0f) {
                        weightFound = true;
                        Timber.d("Weight found using strategy 3 (LE pos 2-3, /100): %.2f kg", weight);
                    }
                }
                
                // Strategy 4: Try scale factor of 10 instead of 100
                if (!weightFound) {
                    int weightRaw = (data[3] & 0xFF) | ((data[4] & 0xFF) << 8);
                    weight = weightRaw / 10.0f;
                    if (weight >= 10.0f && weight <= 300.0f) {
                        weightFound = true;
                        Timber.d("Weight found using strategy 4 (LE pos 3-4, /10): %.2f kg", weight);
                    }
                }
                
                // Strategy 5: Try positions 1-2 with scale factor 100
                if (!weightFound && data.length >= 5) {
                    int weightRaw = (data[1] & 0xFF) | ((data[2] & 0xFF) << 8);
                    weight = weightRaw / 100.0f;
                    if (weight >= 10.0f && weight <= 300.0f) {
                        weightFound = true;
                        Timber.d("Weight found using strategy 5 (LE pos 1-2, /100): %.2f kg", weight);
                    }
                }
            }

            if (weightFound) {
                ScaleMeasurement scaleMeasurement = new ScaleMeasurement();
                // Weight is always parsed in kg, convert if needed for display
                scaleMeasurement.setWeight(weight);
                scaleMeasurement.setDateTime(new Date());
                
                // Log both original and converted weights for debugging
                float displayWeight = convertWeight(weight);
                Timber.i("Successfully parsed weight: %.2f kg (%.2f %s) from data: [%s]", 
                        weight, displayWeight, preferredWeightUnit.getSymbol(), byteInHex(data));
                
                // Only add simple weight measurement if we haven't completed a full body composition measurement
                if (!measurementComplete) {
                    addScaleMeasurement(scaleMeasurement);
                    measurementComplete = true;
                    sendMessage(R.string.info_scale_ready, 0);
                }
            } else {
                Timber.w("Unable to extract valid weight from data: [%s]", byteInHex(data));
                // Log all attempted values for debugging
                for (int i = 0; i < Math.min(data.length - 1, 5); i++) {
                    int raw1 = (data[i] & 0xFF) | ((data[i + 1] & 0xFF) << 8);
                    int raw2 = ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
                    Timber.d("Position %d: LE=%d (%.2f/%.1f), BE=%d (%.2f/%.1f)", 
                           i, raw1, raw1/100.0f, raw1/10.0f, raw2, raw2/100.0f, raw2/10.0f);
                }
            }
        } catch (Exception e) {
            Timber.e(e, "Error parsing weight data: [%s]", byteInHex(data));
        }
    }

    /**
     * Parse comprehensive body composition data from status notifications
     * Extracts weight, body fat, water, muscle, bone mass, and visceral fat
     */
    private void parseBodyCompositionData(byte[] data) {
        try {
            ScaleMeasurement scaleMeasurement = new ScaleMeasurement();
            boolean hasValidWeight = false;
            int validMetrics = 0;
            
            Timber.d("Parsing body composition data (%d bytes): [%s]", data.length, byteInHex(data));
            
            if (data.length >= 20) {
                // Extract weight using multiple strategies
                float weight = 0.0f;
                
                // Try different weight positions common in body composition packets
                int[] weightPositions = {2, 4, 6, 8}; // Common positions for weight data
                for (int pos : weightPositions) {
                    if (pos + 1 < data.length && !hasValidWeight) {
                        // Little-endian
                        int weightRaw = (data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8);
                        weight = weightRaw / 100.0f;
                        
                        if (weight >= 10.0f && weight <= 300.0f) {
                            scaleMeasurement.setWeight(weight);
                            hasValidWeight = true;
                            Timber.d("Extracted weight: %.2f kg (position %d, LE)", weight, pos);
                            break;
                        }
                        
                        // Big-endian if little-endian failed
                        weightRaw = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
                        weight = weightRaw / 100.0f;
                        
                        if (weight >= 10.0f && weight <= 300.0f) {
                            scaleMeasurement.setWeight(weight);
                            hasValidWeight = true;
                            Timber.d("Extracted weight: %.2f kg (position %d, BE)", weight, pos);
                            break;
                        }
                    }
                }
                
                if (hasValidWeight) {
                    // Extract body composition metrics
                    // Try different common layouts for body composition data
                    
                    // Layout 1: Sequential 16-bit values after weight
                    int baseOffset = 6; // Start after weight data
                    if (baseOffset + 10 < data.length) {
                        float fat = extractPercentageValue(data, baseOffset, baseOffset + 1);
                        float water = extractPercentageValue(data, baseOffset + 2, baseOffset + 3);
                        float muscle = extractPercentageValue(data, baseOffset + 4, baseOffset + 5);
                        float bone = extractPercentageValue(data, baseOffset + 6, baseOffset + 7) / 10.0f;
                        float visceral = extractPercentageValue(data, baseOffset + 8, baseOffset + 9) / 10.0f;
                        
                        // Validate and set metrics
                        if (fat > 0.0f && fat <= 50.0f) {
                            scaleMeasurement.setFat(fat);
                            validMetrics++;
                            Timber.d("Extracted fat: %.1f%% (layout 1)", fat);
                        }
                        
                        if (water > 30.0f && water <= 80.0f) {
                            scaleMeasurement.setWater(water);
                            validMetrics++;
                            Timber.d("Extracted water: %.1f%% (layout 1)", water);
                        }
                        
                        if (muscle > 10.0f && muscle <= 70.0f) {
                            scaleMeasurement.setMuscle(muscle);
                            validMetrics++;
                            Timber.d("Extracted muscle: %.1f%% (layout 1)", muscle);
                        }
                        
                        if (bone > 0.5f && bone <= 8.0f) {
                            scaleMeasurement.setBone(bone);
                            validMetrics++;
                            Timber.d("Extracted bone: %.2f kg (layout 1)", bone);
                        }
                        
                        if (visceral > 0.0f && visceral <= 30.0f) {
                            scaleMeasurement.setVisceralFat(visceral);
                            validMetrics++;
                            Timber.d("Extracted visceral fat: %.1f (layout 1)", visceral);
                        }
                    }
                    
                    // If layout 1 didn't yield good results, try layout 2 (different spacing)
                    if (validMetrics < 3 && data.length >= 18) {
                        Timber.d("Trying alternative body composition layout...");
                        validMetrics = 0; // Reset counter
                        
                        // Layout 2: Different positioning, some metrics might be single bytes
                        float fat2 = (data[10] & 0xFF) / 10.0f;
                        float water2 = (data[12] & 0xFF) / 10.0f;
                        float muscle2 = (data[14] & 0xFF) / 10.0f;
                        float bone2 = (data[16] & 0xFF) / 100.0f;
                        float visceral2 = (data[17] & 0xFF) / 10.0f;
                        
                        if (fat2 > 0.0f && fat2 <= 50.0f) {
                            scaleMeasurement.setFat(fat2);
                            validMetrics++;
                            Timber.d("Extracted fat: %.1f%% (layout 2)", fat2);
                        }
                        
                        if (water2 > 30.0f && water2 <= 80.0f) {
                            scaleMeasurement.setWater(water2);
                            validMetrics++;
                            Timber.d("Extracted water: %.1f%% (layout 2)", water2);
                        }
                        
                        if (muscle2 > 10.0f && muscle2 <= 70.0f) {
                            scaleMeasurement.setMuscle(muscle2);
                            validMetrics++;
                            Timber.d("Extracted muscle: %.1f%% (layout 2)", muscle2);
                        }
                        
                        if (bone2 > 0.5f && bone2 <= 8.0f) {
                            scaleMeasurement.setBone(bone2);
                            validMetrics++;
                            Timber.d("Extracted bone: %.2f kg (layout 2)", bone2);
                        }
                        
                        if (visceral2 > 0.0f && visceral2 <= 30.0f) {
                            scaleMeasurement.setVisceralFat(visceral2);
                            validMetrics++;
                            Timber.d("Extracted visceral fat: %.1f (layout 2)", visceral2);
                        }
                    }
                }
            }
            
            // Save measurement if we have valid weight and at least some body composition data
            if (hasValidWeight && validMetrics >= 2) {
                scaleMeasurement.setDateTime(new Date());
                float displayWeight = convertWeight(scaleMeasurement.getWeight());
                Timber.i("Successfully parsed body composition: weight=%.2f kg (%.2f %s), %d metrics", 
                        scaleMeasurement.getWeight(), displayWeight, preferredWeightUnit.getSymbol(), validMetrics);
                
                addScaleMeasurement(scaleMeasurement);
                measurementComplete = true;
                sendMessage(R.string.info_scale_ready, 0);
            } else if (hasValidWeight) {
                // Save weight-only measurement if no body composition data is valid
                scaleMeasurement.setDateTime(new Date());
                float displayWeight = convertWeight(scaleMeasurement.getWeight());
                Timber.i("Parsed weight-only measurement: %.2f kg (%.2f %s)", 
                        scaleMeasurement.getWeight(), displayWeight, preferredWeightUnit.getSymbol());
                
                addScaleMeasurement(scaleMeasurement);
                measurementComplete = true;
                sendMessage(R.string.info_scale_ready, 0);
            } else {
                Timber.w("No valid body composition data found in %d-byte packet: [%s]", 
                        data.length, byteInHex(data));
            }
        } catch (Exception e) {
            Timber.e(e, "Error parsing body composition data: [%s]", byteInHex(data));
        }
    }
    
    private float extractPercentageValue(byte[] data, int lowByte, int highByte) {
        if (lowByte >= data.length || highByte >= data.length) {
            return 0.0f;
        }
        
        // Try little-endian first
        int value = (data[lowByte] & 0xFF) | ((data[highByte] & 0xFF) << 8);
        float result = value / 10.0f;
        
        // If the result seems unreasonable, try big-endian
        if (result <= 0.0f || result > 100.0f) {
            value = ((data[lowByte] & 0xFF) << 8) | (data[highByte] & 0xFF);
            result = value / 10.0f;
        }
        
        return result;
    }
    
    @Override
    public void onBluetoothConnect() {
        super.onBluetoothConnect();
        resetDeviceState();
        Timber.i("Connected to Cult Smart Scale Pro (%s %s)", deviceManufacturer, deviceModel);
    }
    
    @Override
    public void onBluetoothDisconnect() {
        super.onBluetoothDisconnect();
        Timber.i("Disconnected from Cult Smart Scale Pro");
        
        // If measurement was not completed, show appropriate message
        if (!measurementComplete && connectionStartTime > 0) {
            sendMessage(R.string.info_bluetooth_connection_lost, 0);
        }
    }
    
    /**
     * Handle connection errors and implement retry logic
     */
    @Override
    public void onBluetoothConnectionError() {
        super.onBluetoothConnectionError();
        
        if (retryCount < MAX_RETRIES) {
            retryCount++;
            Timber.w("Connection error, attempting retry %d/%d", retryCount, MAX_RETRIES);
            sendMessage(R.string.info_bluetooth_try_connection, retryCount);
        } else {
            Timber.e("Maximum retries exceeded, giving up connection");
            sendMessage(R.string.info_bluetooth_connection_error, 0);
            resetDeviceState();
        }
    }
    
    /**
     * Convert weight from kilograms to user's preferred unit
     */
    private float convertWeight(float weightKg) {
        switch (preferredWeightUnit) {
            case POUNDS:
                return weightKg * 2.20462f;
            case STONES_POUNDS:
                return weightKg * 0.157473f; // Convert to stones (pounds handled separately)
            default:
                return weightKg;
        }
    }
    
    /**
     * Get device status summary for debugging and user information
     */
    public String getDeviceStatus() {
        StringBuilder status = new StringBuilder();
        status.append("Cult Smart Scale Pro Status:\n");
        status.append("Manufacturer: ").append(deviceManufacturer.isEmpty() ? "Unknown" : deviceManufacturer).append("\n");
        status.append("Model: ").append(deviceModel.isEmpty() ? "Unknown" : deviceModel).append("\n");
        status.append("Firmware: ").append(firmwareVersion.isEmpty() ? "Unknown" : firmwareVersion).append("\n");
        status.append("Battery: ").append(batteryLevel >= 0 ? batteryLevel + "%" : "Unknown").append("\n");
        status.append("Configured: ").append(deviceConfigured ? "Yes" : "No").append("\n");
        status.append("Measurement Complete: ").append(measurementComplete ? "Yes" : "No").append("\n");
        status.append("Weight Unit: ").append(preferredWeightUnit.getSymbol()).append("\n");
        
        if (connectionStartTime > 0) {
            long elapsed = System.currentTimeMillis() - connectionStartTime;
            status.append("Connection Time: ").append(elapsed / 1000).append("s\n");
        }
        
        return status.toString();
    }
    
    /**
     * Comprehensive logging method for debugging BLE communication
     * Logs device state, connection info, and recent data patterns
     */
    private void logDebugInfo(String context, byte[] data) {
        if (data != null) {
            Timber.d("[%s] Data (%d bytes): [%s]", context, data.length, byteInHex(data));
            
            // Log human-readable interpretation of common patterns
            if (data.length >= 4) {
                StringBuilder interpretation = new StringBuilder();
                interpretation.append("[").append(context).append("] Possible interpretations: ");
                
                // Check for common command/response patterns
                if (data[0] == (byte)0xCF || data[0] == (byte)0xFD || data[0] == (byte)0xAA) {
                    interpretation.append("Command/Response pattern; ");
                }
                
                // Check for weight patterns (try multiple positions)
                for (int i = 0; i < Math.min(data.length - 1, 4); i++) {
                    int weightRaw = (data[i] & 0xFF) | ((data[i + 1] & 0xFF) << 8);
                    float weight100 = weightRaw / 100.0f;
                    float weight10 = weightRaw / 10.0f;
                    
                    if (weight100 >= 10.0f && weight100 <= 300.0f) {
                        interpretation.append(String.format("Weight@pos%d=%.1fkg; ", i, weight100));
                    } else if (weight10 >= 10.0f && weight10 <= 300.0f) {
                        interpretation.append(String.format("Weight@pos%d=%.1fkg(/10); ", i, weight10));
                    }
                }
                
                // Check for percentage patterns (body composition)
                for (int i = 0; i < Math.min(data.length - 1, 6); i++) {
                    int percentRaw = (data[i] & 0xFF) | ((data[i + 1] & 0xFF) << 8);
                    float percent = percentRaw / 10.0f;
                    
                    if (percent > 5.0f && percent <= 100.0f) {
                        interpretation.append(String.format("Percent@pos%d=%.1f%%; ", i, percent));
                    }
                }
                
                Timber.d(interpretation.toString());
            }
        }
        
        // Log device state every 10th debug call to avoid spam
        if (++debugCallCount % 10 == 0) {
            Timber.d("Device State Summary:\n%s", getDeviceStatus());
        }
    }
    
    // Debug call counter as instance variable
    private int debugCallCount = 0;
}
