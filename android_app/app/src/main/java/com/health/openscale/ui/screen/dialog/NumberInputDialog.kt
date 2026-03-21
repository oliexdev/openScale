package com.health.openscale.ui.screen.dialog

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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.health.openscale.R
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.MeasurementTypeIcon
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.utils.ConverterUtils
import com.health.openscale.ui.components.RoundMeasurementIcon
import java.util.Locale

private fun normalizeStLb(st: Int, lb: Int): Pair<Int, Int> {
    if (lb < 0 && st <= 0) return 0 to 0
    var normSt = st
    var normLb = lb
    if (normLb >= 14) {
        normSt += normLb / 14
        normLb %= 14
    } else if (normLb < 0) {
        val borrow = (-normLb + 13) / 14
        if (normSt >= borrow) {
            normSt -= borrow
            normLb += borrow * 14
        } else {
            return 0 to 0
        }
    }
    return maxOf(0, normSt) to maxOf(0, normLb)
}

@Composable
private fun ValueStepper(onStep: (isIncrement: Boolean) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector        = Icons.Default.KeyboardArrowUp,
            contentDescription = stringResource(R.string.content_desc_increase_value, ""),
            modifier           = Modifier.size(24.dp).clickable { onStep(true) },
        )
        Icon(
            imageVector        = Icons.Default.KeyboardArrowDown,
            contentDescription = stringResource(R.string.content_desc_decrease_value, ""),
            modifier           = Modifier.size(24.dp).clickable { onStep(false) },
        )
    }
}

/**
 * A reusable Composable for number input (TextFields, +/- buttons).
 * Does NOT include an AlertDialog wrapper.
 *
 * @param initialValue   The initial value as a String. For ST unit, expected to be a decimal ST
 *                       string (e.g., "10.5").
 * @param inputType      The type of input (FLOAT, INT).
 * @param unit           The unit of the value (KG, LB, ST, NONE, etc.).
 * @param onValueChange  Callback invoked when the value changes. For ST, provides the value as a
 *                       decimal ST string. For other types, provides the raw input string.
 * @param focusRequester Optional — when provided, the main input field registers with it so the
 *                       caller can programmatically request focus (e.g. after a Clear action).
 * @param label          Optional label for the main input field.
 */
@Composable
fun NumberInputField(
    initialValue: String,
    inputType: InputFieldType,
    unit: UnitType,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    label: String? = null,
) {
    val isSt = unit == UnitType.ST

    var textValue by remember(initialValue, unit, inputType) {
        mutableStateOf(
            TextFieldValue(
                text      = initialValue,
                selection = TextRange(initialValue.length),
            )
        )
    }

    var stText by remember(initialValue, unit) {
        mutableStateOf(
            if (isSt) {
                val f = initialValue.replace(",", ".").toFloatOrNull() ?: 0f
                if (initialValue.isBlank() || f < 0f) ""
                else ConverterUtils.decimalStToStLb(f.toDouble()).first.toString()
            } else ""
        )
    }
    var lbText by remember(initialValue, unit) {
        mutableStateOf(
            if (isSt) {
                val f = initialValue.replace(",", ".").toFloatOrNull() ?: 0f
                if (initialValue.isBlank() || f < 0f) ""
                else ConverterUtils.decimalStToStLb(f.toDouble()).second.toString()
            } else ""
        )
    }

    LaunchedEffect(Unit) {
        focusRequester?.requestFocus()
    }

    LaunchedEffect(textValue, stText, lbText, isSt) {
        if (isSt) {
            if (stText.isBlank() && lbText.isBlank()) {
                onValueChange("")
            } else {
                val st = stText.toIntOrNull() ?: 0
                val lb = lbText.toIntOrNull() ?: 0
                val (normSt, normLb) = normalizeStLb(st, lb)
                if (normSt.toString() != stText) stText = normSt.toString()
                if (normLb.toString() != lbText) lbText = normLb.toString()
                val stDec = ConverterUtils.stLbToStDecimal(normSt, normLb)
                onValueChange(String.format(Locale.US, "%.3f", stDec).trimEnd('0').trimEnd('.'))
            }
        } else {
            onValueChange(textValue.text)
        }
    }

    LaunchedEffect(initialValue, unit, inputType) {
        if (isSt) {
            val f = initialValue.replace(",", ".").toFloatOrNull() ?: 0f
            if (initialValue.isBlank() || f < 0f) {
                stText = ""
                lbText = ""
            } else {
                val (s, l) = ConverterUtils.decimalStToStLb(f.toDouble())
                stText = s.toString()
                lbText = l.toString()
            }
        } else {
            textValue = TextFieldValue(
                text      = initialValue,
                selection = TextRange(initialValue.length),
            )
        }
    }

    Column(modifier = modifier) {
        if (isSt) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier              = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value           = stText,
                    onValueChange   = { stText = ConverterUtils.sanitizeDigits(it, maxLen = 3) },
                    label           = { Text(UnitType.ST.displayName) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    trailingIcon    = {
                        ValueStepper { isIncrement ->
                            var cur = stText.toIntOrNull() ?: 0
                            if (isIncrement) cur++ else cur = maxOf(0, cur - 1)
                            stText = cur.toString()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value           = lbText,
                    onValueChange   = { newLbText ->
                        val lb         = ConverterUtils.sanitizeDigits(newLbText, maxLen = 2).toIntOrNull() ?: 0
                        val curSt      = stText.toIntOrNull() ?: 0
                        val (nSt, nLb) = normalizeStLb(curSt, lb)
                        stText = nSt.toString()
                        lbText = nLb.toString()
                    },
                    label           = { Text(UnitType.LB.displayName) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    trailingIcon    = {
                        ValueStepper { isIncrement ->
                            var curSt      = stText.toIntOrNull() ?: 0
                            var curLb      = lbText.toIntOrNull() ?: 0
                            if (isIncrement) curLb++ else curLb--
                            val (nSt, nLb) = normalizeStLb(curSt, curLb)
                            stText = nSt.toString()
                            lbText = nLb.toString()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            OutlinedTextField(
                value         = textValue,
                onValueChange = { incoming ->
                    val filtered = incoming.text.filter { c ->
                        c.isDigit() || c == '.' || c == ',' ||
                                (inputType == InputFieldType.FLOAT && c == '-')
                    }
                    textValue = incoming.copy(text = filtered)
                },
                label           = { label?.let { Text(it) } ?: Text(stringResource(R.string.dialog_title_input_value)) },
                keyboardOptions = KeyboardOptions.Default.copy(
                    keyboardType = when (inputType) {
                        InputFieldType.FLOAT -> KeyboardType.Decimal
                        InputFieldType.INT   -> KeyboardType.Number
                        else                 -> KeyboardType.Text
                    }
                ),
                singleLine   = true,
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (unit.displayName.isNotBlank()) {
                            Text(
                                text     = unit.displayName,
                                modifier = Modifier.padding(end = 8.dp),
                                style    = LocalTextStyle.current.copy(fontSize = 14.sp),
                            )
                        }
                        if (inputType == InputFieldType.FLOAT || inputType == InputFieldType.INT) {
                            ValueStepper { isIncrement ->
                                val numStr = textValue.text.replace(',', '.')
                                if (inputType == InputFieldType.INT) {
                                    val cur     = numStr.toIntOrNull() ?: 0
                                    val newText = (if (isIncrement) cur + 1 else cur - 1).toString()
                                    textValue   = TextFieldValue(newText, TextRange(newText.length))
                                } else {
                                    val cur  = numStr.toFloatOrNull() ?: 0f
                                    val step = when (unit) {
                                        UnitType.KG, UnitType.LB,
                                        UnitType.PERCENT, UnitType.INCH -> 0.1f
                                        UnitType.CM                     -> 0.5f
                                        UnitType.KCAL                   -> 10f
                                        else                             -> 0.1f
                                    }
                                    val newText = String.format(
                                        Locale.US,
                                        if (step >= 1.0f) "%.0f" else "%.1f",
                                        if (isIncrement) cur + step else cur - step,
                                    )
                                    textValue = TextFieldValue(newText, TextRange(newText.length))
                                }
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (focusRequester != null) Modifier.focusRequester(focusRequester)
                        else Modifier
                    ),
            )
        }
    }
}

/**
 * The main AlertDialog for number input, using [NumberInputField].
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
    onConfirm: (String) -> Unit,
) {
    var currentValue   by remember(initialValue, unit, inputType) { mutableStateOf(initialValue) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(initialValue, unit, inputType) {
        currentValue = initialValue
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(currentValue) }) {
                Text(stringResource(R.string.dialog_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                currentValue = ""
                focusRequester.requestFocus()
            }) {
                Text(stringResource(R.string.clear_button))
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_button))
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundMeasurementIcon(
                    icon           = measurementIcon.resource,
                    backgroundTint = iconBackgroundColor,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(title)
            }
        },
        text = {
            NumberInputField(
                initialValue   = currentValue,
                inputType      = inputType,
                unit           = unit,
                onValueChange  = { currentValue = it },
                focusRequester = focusRequester,
                modifier       = Modifier.padding(top = 8.dp),
                label          = stringResource(R.string.dialog_title_input_value),
            )
        },
    )
}