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
package com.health.openscale.ui.screen.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    // Single-field value for non-ST modes (kept exactly as before).
    var value by remember(unit, initialValue) { mutableStateOf(initialValue) }

    // Separate text states for ST mode (stones & pounds).
    val isSt = unit == UnitType.ST
    var stText by remember(unit, initialValue) {
        mutableStateOf(
            if (isSt) {
                val init = initialValue.replace(",", ".")
                if (init.isBlank()) "" else stDecimalToStLb(init.toFloatOrNull() ?: 0f).first.toString()
            } else ""
        )
    }
    var lbText by remember(unit, initialValue) {
        mutableStateOf(
            if (isSt) {
                val init = initialValue.replace(",", ".")
                if (init.isBlank()) "" else stDecimalToStLb(init.toFloatOrNull() ?: 0f).second.toString()
            } else ""
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                if (isSt) {
                    // ST mode: convert (st, lb) back to decimal stones for persistence.
                    if (stText.isBlank() && lbText.isBlank()) {
                        onConfirm("") // empty input stays empty
                    } else {
                        val st = stText.toIntOrNull() ?: 0
                        val lb = lbText.toIntOrNull() ?: 0
                        val (stN, lbN) = normalizeStLb(st, lb)
                        val stDec = stLbToStDecimal(stN, lbN)
                        onConfirm(String.format(Locale.US, "%.3f", stDec).trimEnd('0').trimEnd('.'))
                    }
                } else {
                    // Non-ST: return raw text unchanged.
                    onConfirm(value)
                }
                onDismiss()
            }) {
                Text(stringResource(R.string.dialog_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_button))
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Measurement icon bubble on the left
                RoundMeasurementIcon(
                    icon = measurementIcon.resource,
                    backgroundTint = iconBackgroundColor,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(title)
            }
        },
        text = {
            if (isSt) {
                // --- ST MODE: two separate OutlinedTextFields with unit as trailingIcon ---

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Stones input
                    OutlinedTextField(
                        value = stText,
                        onValueChange = { stText = sanitizeDigits(it, maxLen = 3) },
                        label = null, // no "Input value" label in ST mode
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        trailingIcon = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.End
                            ) {
                                // Fixed unit label on the right, like your single-field pattern
                                Text(
                                    text = "st",
                                    modifier = Modifier.padding(end = 8.dp),
                                    style = LocalTextStyle.current.copy(fontSize = 14.sp)
                                )
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowUp,
                                        contentDescription = stringResource(R.string.trend_increased_desc),
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clickable {
                                                val st = stText.toIntOrNull() ?: 0
                                                stText = (st + 1).toString()
                                            }
                                    )
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = stringResource(R.string.trend_decreased_desc),
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clickable {
                                                val st = stText.toIntOrNull() ?: 0
                                                stText = maxOf(0, st - 1).toString()
                                            }
                                    )
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )

                    // Pounds input (0..13; normalized on arrows and on confirm)
                    OutlinedTextField(
                        value = lbText,
                        onValueChange = { lbText = sanitizeDigits(it, maxLen = 2) },
                        label = null, // no "Input value" label in ST mode
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        trailingIcon = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.End
                            ) {
                                Text(
                                    text = "lb",
                                    modifier = Modifier.padding(end = 8.dp),
                                    style = LocalTextStyle.current.copy(fontSize = 14.sp)
                                )
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowUp,
                                        contentDescription = stringResource(R.string.trend_increased_desc),
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clickable {
                                                var st = stText.toIntOrNull() ?: 0
                                                var lb = (lbText.toIntOrNull() ?: 0) + 1
                                                if (lb >= 14) { st += lb / 14; lb %= 14 }
                                                stText = st.toString()
                                                lbText = lb.toString()
                                            }
                                    )
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = stringResource(R.string.trend_decreased_desc),
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clickable {
                                                var st = stText.toIntOrNull() ?: 0
                                                var lb = (lbText.toIntOrNull() ?: 0) - 1
                                                if (lb < 0) {
                                                    if (st > 0) { st -= 1; lb += 14 } else { lb = 0 }
                                                }
                                                stText = st.toString()
                                                lbText = lb.toString()
                                            }
                                    )
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                // --- NON-ST MODE: your original single input with trailing unit + arrows ---
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.dialog_title_input_value)) },
                    keyboardOptions = KeyboardOptions.Default.copy(
                        keyboardType = when (inputType) {
                            InputFieldType.FLOAT -> KeyboardType.Decimal
                            InputFieldType.INT -> KeyboardType.Number
                            else -> KeyboardType.Text
                        }
                    ),
                    trailingIcon = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End
                        ) {
                            Text(
                                text = unit.displayName,
                                modifier = Modifier.padding(end = 8.dp),
                                style = LocalTextStyle.current.copy(fontSize = 14.sp)
                            )
                            if (inputType == InputFieldType.INT || inputType == InputFieldType.FLOAT) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowUp,
                                        contentDescription = stringResource(R.string.trend_increased_desc),
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clickable {
                                                value = incrementValue(value, inputType)
                                            }
                                    )
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = stringResource(R.string.trend_decreased_desc),
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clickable {
                                                value = decrementValue(value, inputType)
                                            }
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )
}

/** Stepper +1 for the single-field mode (kept from your original). */
fun incrementValue(value: String, type: InputFieldType): String {
    return when (type) {
        InputFieldType.INT -> (value.toIntOrNull()?.plus(1) ?: 1).toString()
        InputFieldType.FLOAT -> (value.toFloatOrNull()?.plus(1f) ?: 1f).toString()
        else -> value
    }
}

/** Stepper -1 for the single-field mode (kept from your original). */
fun decrementValue(value: String, type: InputFieldType): String {
    return when (type) {
        InputFieldType.INT -> (value.toIntOrNull()?.minus(1) ?: 0).toString()
        InputFieldType.FLOAT -> (value.toFloatOrNull()?.minus(1f) ?: 0f).toString()
        else -> value
    }
}

/* ----------------------- ST helpers (private) ----------------------- */

/** Convert decimal stones (e.g., "12.5") to (st, lb) pair for display. */
private fun stDecimalToStLb(stDecimal: Float): Pair<Int, Int> {
    val totalLb = stDecimal * 14f
    val st = (totalLb / 14f).toInt()
    val lb = round(totalLb - st * 14f).toInt().coerceIn(0, 13)
    return st to lb
}

/** Convert (st, lb) pair back to decimal stones. */
private fun stLbToStDecimal(st: Int, lb: Int): Float {
    val totalLb = st * 14 + lb
    return totalLb / 14f
}

/** Keep only digits; restrict length to avoid unrealistic inputs. */
private fun sanitizeDigits(s: String, maxLen: Int = 3): String =
    s.filter { it.isDigit() }.take(maxLen)

/** Normalize so that 0 <= lb < 14, carrying/borrowing against stones if needed. */
private fun normalizeStLb(st: Int, lb: Int): Pair<Int, Int> {
    if (lb >= 0) return (st + lb / 14) to (lb % 14)
    // lb < 0: borrow from stones if possible
    return if (st > 0) {
        val borrow = ((-lb) + 13) / 14
        val stOut = maxOf(0, st - borrow)
        val lbOut = (st - stOut) * 14 + lb
        stOut to lbOut
    } else {
        0 to 0
    }
}
