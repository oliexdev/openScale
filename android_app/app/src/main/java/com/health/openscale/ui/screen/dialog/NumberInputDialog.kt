package com.health.openscale.ui.screen.dialog

import android.R.attr.inputType
import android.R.attr.onClick
import android.R.attr.type
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.health.openscale.R
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.MeasurementTypeIcon
import com.health.openscale.core.data.UnitType
import com.health.openscale.ui.components.RoundMeasurementIcon
import java.util.Locale
import kotlin.math.round

private fun sanitizeDigits(input: String, maxLen: Int): String {
    return input.filter { it.isDigit() }.take(maxLen)
}

private fun stDecimalToStLb(stDecimal: Float): Pair<Int, Int> {
    if (stDecimal < 0f) return Pair(0,0)
    val totalPounds = round(stDecimal * 14f).toInt()
    val stones = totalPounds / 14
    val pounds = totalPounds % 14
    return Pair(stones, pounds)
}

private fun stLbToStDecimal(st: Int, lb: Int): Float {
    if (st < 0 || lb < 0) return 0f
    return st + (lb / 14.0f)
}

private fun normalizeStLb(st: Int, lb: Int): Pair<Int, Int> {
    if (lb < 0 && st <= 0) return Pair(0, 0)
    var normalizedSt = st
    var normalizedLb = lb

    if (normalizedLb >= 14) {
        normalizedSt += normalizedLb / 14
        normalizedLb %= 14
    } else if (normalizedLb < 0) {
        val neededStonesToBorrow = (-normalizedLb + 13) / 14
        if (normalizedSt >= neededStonesToBorrow) {
            normalizedSt -= neededStonesToBorrow
            normalizedLb += neededStonesToBorrow * 14
        } else {
            return Pair(0,0) // Cannot make pounds positive without making stones negative
        }
    }
    return Pair(maxOf(0, normalizedSt), maxOf(0, normalizedLb))
}

@Composable
private fun ValueStepper(onStep: (isIncrement: Boolean) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = Icons.Default.KeyboardArrowUp,
            contentDescription = stringResource(R.string.content_desc_increase_value, ""),
            modifier = Modifier.size(24.dp).clickable { onStep(true) }
        )
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = stringResource(R.string.content_desc_decrease_value, ""),
            modifier = Modifier.size(24.dp).clickable { onStep(false) }
        )
    }
}

/**
 * A reusable Composable for number input (TextFields, +/- buttons).
 * Does NOT include an AlertDialog wrapper.
 *
 * @param initialValue The initial value as a String. For ST unit, this is expected to be a decimal ST string (e.g., "10.5").
 * @param inputType The type of input (FLOAT, INT).
 * @param unit The unit of the value (KG, LB, ST, NONE, etc.).
 * @param onValueChange Callback invoked when the value changes.
 *                      For ST, it provides the value as a *decimal ST string*.
 *                      For other types, it provides the raw input string.
 * @param label Optional label for the main input field.
 */
@Composable
fun NumberInputField(
    initialValue: String,
    inputType: InputFieldType,
    unit: UnitType,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null
) {
    // Internal state for the text field in non-ST mode
    var textValue by remember(initialValue, unit, inputType) { mutableStateOf(initialValue) }

    val isSt = unit == UnitType.ST
    // Internal states for stones and pounds in ST mode
    var stText by remember(initialValue, unit) {
        mutableStateOf(
            if (isSt) {
                val initFloat = initialValue.replace(",", ".").toFloatOrNull() ?: 0f
                if (initialValue.isBlank() || initFloat < 0) "" else stDecimalToStLb(initFloat).first.toString()
            } else ""
        )
    }
    var lbText by remember(initialValue, unit) {
        mutableStateOf(
            if (isSt) {
                val initFloat = initialValue.replace(",", ".").toFloatOrNull() ?: 0f
                if (initialValue.isBlank() || initFloat < 0) "" else stDecimalToStLb(initFloat).second.toString()
            } else ""
        )
    }

    // Effect to call onValueChange when internal text states change
    LaunchedEffect(textValue, stText, lbText, isSt) {
        if (isSt) {
            if (stText.isBlank() && lbText.isBlank()) {
                onValueChange("")
            } else {
                val st = stText.toIntOrNull() ?: 0
                val lb = lbText.toIntOrNull() ?: 0
                val (normSt, normLb) = normalizeStLb(st, lb) // Normalize before converting

                // Update UI states if normalization changed them
                if (normSt.toString() != stText) stText = normSt.toString()
                if (normLb.toString() != lbText) lbText = normLb.toString()

                val stDec = stLbToStDecimal(normSt, normLb)
                onValueChange(String.format(Locale.US, "%.3f", stDec).trimEnd('0').trimEnd('.'))
            }
        } else {
            onValueChange(textValue)
        }
    }

    // Effect to update internal states if initialValue is changed from the outside
    LaunchedEffect(initialValue, unit, inputType) {
        if (isSt) {
            val initFloat = initialValue.replace(",", ".").toFloatOrNull() ?: 0f
            if (initialValue.isBlank() || initFloat < 0) {
                stText = ""
                lbText = ""
            } else {
                val (s, l) = stDecimalToStLb(initFloat)
                stText = s.toString()
                lbText = l.toString()
            }
        } else {
            textValue = initialValue
        }
    }

    Column(modifier = modifier) {
        if (isSt) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField( // Stones input
                    value = stText,
                    onValueChange = { stText = sanitizeDigits(it, maxLen = 3) },
                    label = { Text(UnitType.ST.displayName) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    trailingIcon = {
                        ValueStepper { isIncrement ->
                            var currentSt = stText.toIntOrNull() ?: 0
                            if (isIncrement) currentSt++ else currentSt = maxOf(0, currentSt - 1)
                            stText = currentSt.toString()
                            // onValueChange is triggered by the LaunchedEffect above
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField( // Pounds input
                    value = lbText,
                    onValueChange = { newLbText ->
                        val sanitizedLb = sanitizeDigits(newLbText, maxLen = 2)
                        val lbNumeric = sanitizedLb.toIntOrNull() ?: 0
                        val currentSt = stText.toIntOrNull() ?: 0
                        val (normSt, normLb) = normalizeStLb(currentSt, lbNumeric)
                        stText = normSt.toString() // Stones might change due to normalization
                        lbText = normLb.toString() // Set normalized pounds
                    },
                    label = { Text(UnitType.LB.displayName)  },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    trailingIcon = {
                        ValueStepper { isIncrement ->
                            var currentSt = stText.toIntOrNull() ?: 0
                            var currentLb = lbText.toIntOrNull() ?: 0
                            if (isIncrement) currentLb++ else currentLb--
                            val (normSt, normLb) = normalizeStLb(currentSt, currentLb)
                            stText = normSt.toString()
                            lbText = normLb.toString()
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        } else { // For KG, LB, %, INT, FLOAT etc.
            OutlinedTextField(
                value = textValue,
                onValueChange = {
                    val filteredText = it.filter { char -> char.isDigit() || char == '.' || char == ',' || (inputType == InputFieldType.FLOAT && char == '-') }
                    textValue = filteredText
                },
                label = { label?.let { Text(it) } ?: Text(stringResource(R.string.dialog_title_input_value)) },
                keyboardOptions = KeyboardOptions.Default.copy(
                    keyboardType = when (inputType) {
                        InputFieldType.FLOAT -> KeyboardType.Decimal
                        InputFieldType.INT -> KeyboardType.Number
                        else -> KeyboardType.Text // Should ideally not happen for number inputs
                    }
                ),
                singleLine = true,
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (unit.displayName.isNotBlank()) {
                            Text(
                                text = unit.displayName,
                                modifier = Modifier.padding(end = 8.dp),
                                style = LocalTextStyle.current.copy(fontSize = 14.sp)
                            )
                        }
                        if (inputType == InputFieldType.FLOAT || inputType == InputFieldType.INT) {
                            ValueStepper { isIncrement ->
                                val currentNumStr = textValue.replace(',', '.')
                                if (inputType == InputFieldType.INT) {
                                    val currentNum = currentNumStr.toIntOrNull() ?: 0
                                    textValue = (if (isIncrement) currentNum + 1 else currentNum - 1).toString()
                                } else { // FLOAT
                                    val currentNum = currentNumStr.toFloatOrNull() ?: 0f
                                    val step = when (unit) {
                                        UnitType.KG, UnitType.LB, UnitType.PERCENT, UnitType.INCH -> 0.1f
                                        UnitType.CM -> 0.5f
                                        UnitType.KCAL -> 10f
                                        else -> 0.1f
                                    }
                                    val newValue = if (isIncrement) currentNum + step else currentNum - step
                                    textValue = String.format(Locale.US, if (step >= 1.0f) "%.0f" else "%.1f", newValue)
                                        .replace(',', '.') // Ensure dot for consistency
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * The main AlertDialog for number input, now using [NumberInputField].
 */
@Composable
fun NumberInputDialog(
    title: String,
    initialValue: String,
    inputType: InputFieldType,
    unit: UnitType,
    measurementIcon: MeasurementTypeIcon,
    iconBackgroundColor: Color,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    // State to hold the current value from NumberInputField, to be used by onConfirm
    var currentValueForDialog by remember(initialValue, unit, inputType) { mutableStateOf(initialValue) }

    // Sync currentValueForDialog if initialValue changes from outside
    LaunchedEffect(initialValue, unit, inputType) {
        currentValueForDialog = initialValue
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                // NumberInputField's onValueChange should have already provided the
                // correctly formatted (e.g., decimal ST) or raw string.
                onConfirm(currentValueForDialog)
            }) {
                Text(stringResource(R.string.dialog_ok))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    currentValueForDialog = ""
                }
            ) {
                Text(stringResource(R.string.clear_button))
            }

            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_button))
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundMeasurementIcon(
                    icon = measurementIcon.resource,
                    backgroundTint = iconBackgroundColor,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(title)
            }
        },
        text = {
            NumberInputField(
                initialValue = currentValueForDialog,
                inputType = inputType,
                unit = unit,
                onValueChange = { newValueFromField ->
                    currentValueForDialog = newValueFromField // Update the dialog's current value
                },
                modifier = Modifier.padding(top = 8.dp),
                label = stringResource(R.string.dialog_title_input_value) // Default label for the input field
            )
        }
    )
}

