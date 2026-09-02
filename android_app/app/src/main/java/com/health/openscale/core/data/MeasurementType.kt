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
package com.health.openscale.core.data

import android.content.Context
import androidx.annotation.StringRes
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.health.openscale.R

/**
 * A measurement type: one row per quantity the app tracks.
 *
 * Two halves, one concept. The **row** (this entity) is user state — the chosen unit,
 * colour, icon and flags, all editable. The **[Key]** is vocabulary and defaults — what a
 * quantity is called, which units are allowed and how a fresh row starts. Predefined keys
 * live as constants on the companion ([WEIGHT], [BODY_FAT], …); scale handlers declare
 * their own via [deviceFloat] & friends; user-created types have no [Key] at all, only a
 * row.
 *
 * Both meet in [identity], the stable namespaced string this row is unique by:
 *
 * - `builtin.weight` — predefined, resolvable back to its [Key] via [keyOf]
 * - `ble.segmental.fat.left_arm` — contributed by a scale handler
 * - `user.schritte` — created by the user
 *
 * The prefixes are applied by the factories, never typed by hand, so a handler cannot
 * claim the builtin or user namespace by accident. An identity is frozen once assigned —
 * renames, language switches and unit edits never change it.
 */
@Entity(
    // Unique: the identity IS what makes a measurement type itself. SQLite enforces what
    // the pre-16 schema could not — a second `builtin.weight` row cannot exist.
    indices = [Index(value = ["identity"], unique = true)]
)
data class MeasurementType(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    /**
     * Stable namespaced identity, see class doc. Blank means "not assigned yet": the type
     * editor constructs rows without one and MeasurementTypeCrudUseCases assigns the
     * `user.*` identity on save.
     */
    val identity: String = "",
    val name: String? = null,
    val color: Int = 0,
    val icon: MeasurementTypeIcon = MeasurementTypeIcon.IC_DEFAULT,
    val unit: UnitType = UnitType.NONE,
    val inputType: InputFieldType = InputFieldType.FLOAT,
    val displayOrder: Int = 0,
    val isDerived: Boolean = false,
    val isEnabled: Boolean = true,
    val isPinned: Boolean = false,
    val isOnRightYAxis: Boolean = false,
    val isInternal: Boolean = false
) {
    /** The predefined key behind this row, or null for `ble.*`/`user.*` rows. */
    val key: Key<*>?
        get() = keyOf(identity)

    @Ignore
    fun isBuiltIn(): Boolean = identity.startsWith(BUILTIN_PREFIX)

    /** True for a type a scale handler contributed. Its protocol owns unit and input type. */
    @Ignore
    fun isDeviceOwned(): Boolean = identity.startsWith(DEVICE_PREFIX)

    /** True for a type the user created themselves. */
    @Ignore
    fun isUserOwned(): Boolean = identity.startsWith(USER_PREFIX)

    /**
     * Display name: predefined types resolve their localized string resource (the stored
     * [name] stays null and is ignored), all other types show the stored [name].
     */
    @Ignore
    fun getDisplayName(context: Context): String {
        val predefined = key
        return when {
            predefined != null -> context.getString(predefined.nameResId)
            !name.isNullOrBlank() -> name
            else -> context.getString(R.string.measurement_type_custom_default_name)
        }
    }

    /**
     * One vocabulary entry — a quantity the app can track, identified by a namespaced
     * identity string. [T] is bound to [inputType] by the factories, so the value a
     * handler writes into a ScaleMeasurement and the column it lands in cannot disagree:
     * `m[aTextKey] = 1.5f` does not compile.
     */
    // The constructor is internal out of necessity, not intent: Kotlin cannot restrict a
    // nested class's constructor to the outer companion. The namespace guarantee is
    // enforced where rows are created instead — resolveOrCreate materializes only ble.*
    // keys, and the crud use case accepts only user.* or registry identities.
    class Key<T : Any> internal constructor(
        val identity: String,
        @param:StringRes val nameResId: Int,
        val inputType: InputFieldType,
        /**
         * The unit handler-supplied raw values arrive in — for typed keys the value class
         * (Kg, Percent, ...) enforces it at compile time, and this names the same unit for
         * the connector, which converts from it to the unit the user chose.
         */
        val wireUnit: UnitType,
        val allowedUnitTypes: List<UnitType>,
        val allowedInputTypes: List<InputFieldType>,
        // Seed defaults — how a fresh row for this key starts out.
        val defaultUnit: UnitType,
        val defaultColor: Int,
        val defaultIcon: MeasurementTypeIcon,
        val isDerived: Boolean,
        val defaultEnabled: Boolean,
        val defaultPinned: Boolean,
        val defaultOnRightYAxis: Boolean,
        val isInternal: Boolean
    ) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Key<*> && other.identity == identity)

        override fun hashCode(): Int = identity.hashCode()

        override fun toString(): String = "Key($identity)"
    }

    /**
     * The predefined vocabulary — replaces both the former MeasurementTypeKey enum and
     * the former seed list in OpenScaleApp, which had to be kept in sync by hand.
     *
     * INVARIANT (guarded by a test): every builtin id is the lowercased historical enum
     * name (`body_fat`, `caliper_1`). That single rule keeps two contracts correct at
     * once — the 15→16 migration rewrites `'builtin.' || lower(key)`, and the CSV export
     * keeps its historical `BODY_FAT` headers.
     *
     * Declaration order is the canonical display order (migrations re-stamp displayOrder
     * from it), which is why it follows the former seed list, not the former enum.
     */
    companion object {
        const val BUILTIN_PREFIX = "builtin."
        const val DEVICE_PREFIX = "ble."
        const val USER_PREFIX = "user."
        /** The pre-16 `key` column sentinel every custom row shared; only the 15→16
         *  migration still needs it to recognise legacy rows. */
        const val LEGACY_CUSTOM_KEY = "CUSTOM"

        private const val DEVICE_DEFAULT_COLOR = 0xFFFFA726.toInt()

        private val ordered = mutableListOf<Key<*>>()

        private fun <T : Any> reg(key: Key<T>): Key<T> {
            ordered += key
            return key
        }

        private val WEIGHT_UNITS = listOf(UnitType.KG, UnitType.LB, UnitType.ST)
        private val COMPOSITION_UNITS = listOf(UnitType.PERCENT, UnitType.KG, UnitType.LB, UnitType.ST)
        private val LENGTH_UNITS = listOf(UnitType.CM, UnitType.INCH)
        private val NONE_UNIT = listOf(UnitType.NONE)

        // T is the unit value class the key's raw values arrive as (Kg, Percent, ...);
        // Float only for dimensionless quantities. wire must name the same unit.
        private fun <T : Any> builtinFloat(
            id: String, @StringRes nameRes: Int, units: List<UnitType>, color: Long,
            icon: MeasurementTypeIcon, wire: UnitType = units.first(),
            derived: Boolean = false, enabled: Boolean = true, pinned: Boolean = false,
            rightAxis: Boolean = false, isInternal: Boolean = false
        ): Key<T> = reg(
            Key(
                BUILTIN_PREFIX + id, nameRes, InputFieldType.FLOAT, wire, units,
                listOf(InputFieldType.FLOAT), units.first(), color.toInt(), icon,
                derived, enabled, pinned, rightAxis, isInternal
            )
        )

        /** Body weight — raw values arrive as [Kg]. */
        val WEIGHT: Key<Kg> = builtinFloat("weight", R.string.measurement_type_weight, WEIGHT_UNITS, 0xFF7E57C2, MeasurementTypeIcon.IC_WEIGHT, pinned = true, rightAxis = true)
        /** Body mass index — dimensionless; usually derived by the app, set it only if the scale reports its own. */
        val BMI: Key<Float> = builtinFloat("bmi", R.string.measurement_type_bmi, NONE_UNIT, 0xFFFFCA28, MeasurementTypeIcon.IC_BMI, derived = true, pinned = true)
        /** Body fat — raw values arrive as [Percent] of body weight. */
        val BODY_FAT: Key<Percent> = builtinFloat("body_fat", R.string.measurement_type_body_fat, COMPOSITION_UNITS, 0xFFEF5350, MeasurementTypeIcon.IC_BODY_FAT, pinned = true)
        /** Body water — raw values arrive as [Percent] of body weight. */
        val WATER: Key<Percent> = builtinFloat("water", R.string.measurement_type_water, COMPOSITION_UNITS, 0xFF29B6F6, MeasurementTypeIcon.IC_WATER, pinned = true)
        /** Muscle mass — raw values arrive as [Percent] of body weight. */
        val MUSCLE: Key<Percent> = builtinFloat("muscle", R.string.measurement_type_muscle, COMPOSITION_UNITS, 0xFF66BB6A, MeasurementTypeIcon.IC_MUSCLE, pinned = true)
        /** Lean body mass — raw values arrive as [Kg]. */
        val LBM: Key<Kg> = builtinFloat("lbm", R.string.measurement_type_lbm, WEIGHT_UNITS, 0xFF4DBAC0, MeasurementTypeIcon.IC_LBM)
        /** Bone mass — raw values arrive as [Kg]. */
        val BONE: Key<Kg> = builtinFloat("bone", R.string.measurement_type_bone, listOf(UnitType.KG, UnitType.LB), 0xFFBDBDBD, MeasurementTypeIcon.IC_BONE)
        /** Waist circumference — raw values arrive as [Cm]. */
        val WAIST: Key<Cm> = builtinFloat("waist", R.string.measurement_type_waist, LENGTH_UNITS, 0xFF78909C, MeasurementTypeIcon.IC_WAIST)
        /** Waist-to-hip ratio — dimensionless, derived by the app. */
        val WHR: Key<Float> = builtinFloat("whr", R.string.measurement_type_whr, NONE_UNIT, 0xFFFFA726, MeasurementTypeIcon.IC_WHR, derived = true)
        /** Waist-to-height ratio — dimensionless, derived by the app. */
        val WHTR: Key<Float> = builtinFloat("whtr", R.string.measurement_type_whtr, NONE_UNIT, 0xFFFF7043, MeasurementTypeIcon.IC_WHTR, derived = true)
        /** Hip circumference — raw values arrive as [Cm]. */
        val HIPS: Key<Cm> = builtinFloat("hips", R.string.measurement_type_hips, LENGTH_UNITS, 0xFF5C6BC0, MeasurementTypeIcon.IC_HIPS)
        /** Visceral fat rating — dimensionless vendor scale (typically 1..59). */
        val VISCERAL_FAT: Key<Float> = builtinFloat("visceral_fat", R.string.measurement_type_visceral_fat, NONE_UNIT, 0xFFD84315, MeasurementTypeIcon.IC_VISCERAL_FAT)
        /** Chest circumference — raw values arrive as [Cm]. */
        val CHEST: Key<Cm> = builtinFloat("chest", R.string.measurement_type_chest, LENGTH_UNITS, 0xFF8E24AA, MeasurementTypeIcon.IC_CHEST)
        /** Thigh circumference — raw values arrive as [Cm]. */
        val THIGH: Key<Cm> = builtinFloat("thigh", R.string.measurement_type_thigh, LENGTH_UNITS, 0xFFA1887F, MeasurementTypeIcon.IC_THIGH)
        /** Biceps circumference — raw values arrive as [Cm]. */
        val BICEPS: Key<Cm> = builtinFloat("biceps", R.string.measurement_type_biceps, LENGTH_UNITS, 0xFFEC407A, MeasurementTypeIcon.IC_BICEPS)
        /** Neck circumference — raw values arrive as [Cm]. */
        val NECK: Key<Cm> = builtinFloat("neck", R.string.measurement_type_neck, LENGTH_UNITS, 0xFFB0BEC5, MeasurementTypeIcon.IC_NECK)
        /** Caliper skinfold site 1 — raw values arrive as [Cm]. */
        val CALIPER_1: Key<Cm> = builtinFloat("caliper_1", R.string.measurement_type_caliper1, LENGTH_UNITS, 0xFFFFF59D, MeasurementTypeIcon.IC_CALIPER1)
        /** Caliper skinfold site 2 — raw values arrive as [Cm]. */
        val CALIPER_2: Key<Cm> = builtinFloat("caliper_2", R.string.measurement_type_caliper2, LENGTH_UNITS, 0xFFFFE082, MeasurementTypeIcon.IC_CALIPER2)
        /** Caliper skinfold site 3 — raw values arrive as [Cm]. */
        val CALIPER_3: Key<Cm> = builtinFloat("caliper_3", R.string.measurement_type_caliper3, LENGTH_UNITS, 0xFFFFCC80, MeasurementTypeIcon.IC_CALIPER3)
        /** Caliper body fat — **%**, derived by the app from the three sites. */
        val CALIPER: Key<Percent> = builtinFloat("caliper", R.string.measurement_type_fat_caliper, listOf(UnitType.PERCENT), 0xFFFB8C00, MeasurementTypeIcon.IC_FAT_CALIPER, derived = true)
        /** Basal metabolic rate — raw values arrive as [Kcal] per day (set it only if the scale reports its own; the app derives it otherwise). */
        val BMR: Key<Kcal> = builtinFloat("bmr", R.string.measurement_type_bmr, listOf(UnitType.KCAL), 0xFFAB47BC, MeasurementTypeIcon.IC_BMR, derived = true)
        /** Total daily energy expenditure — **kcal/day**, derived by the app. */
        val TDEE: Key<Kcal> = builtinFloat("tdee", R.string.measurement_type_tdee, listOf(UnitType.KCAL), 0xFF26A69A, MeasurementTypeIcon.IC_TDEE, derived = true)
        /** Heart rate — raw values arrive as [Bpm]. */
        val HEART_RATE: Key<Bpm> = reg(
            Key(
                BUILTIN_PREFIX + "heart_rate", R.string.measurement_type_heart_rate,
                InputFieldType.INT, UnitType.BPM, listOf(UnitType.BPM), listOf(InputFieldType.INT),
                UnitType.BPM, 0xFFE91E63.toInt(), MeasurementTypeIcon.IC_M_HEART_RATE,
                false, true, false, false, false
            )
        )
        /** Bioimpedance (high/single frequency) — raw values arrive as [Ohm]. */
        val IMPEDANCE: Key<Ohm> = builtinFloat("impedance", R.string.measurement_type_impedance, listOf(UnitType.OHM), 0xFF607D8B, MeasurementTypeIcon.IC_DEFAULT, enabled = false, isInternal = true)
        /** Bioimpedance (low frequency) — raw values arrive as [Ohm]. */
        val IMPEDANCE_LOW: Key<Ohm> = builtinFloat("impedance_low", R.string.measurement_type_impedance_low, listOf(UnitType.OHM), 0xFF455A64, MeasurementTypeIcon.IC_DEFAULT, enabled = false, isInternal = true)
        // ECW, ICW and BCM are deliberately absent: only a dual-frequency BIA scale
        // reports them, so they live in the ble.* namespace, declared by the S400 handler.
        // MIGRATION_15_16 retires the rows older installs carry.
        /** Protein — raw values arrive as [Percent] of body weight. */
        val PROTEIN: Key<Percent> = builtinFloat("protein", R.string.measurement_type_protein, COMPOSITION_UNITS, 0xFF9CCC65, MeasurementTypeIcon.IC_M_PROTEIN)
        /** Calorie intake — **kcal**, entered by the user, not by scales. */
        val CALORIES: Key<Kcal> = builtinFloat("calories", R.string.measurement_type_calories, listOf(UnitType.KCAL), 0xFF4CAF50, MeasurementTypeIcon.IC_CALORIES)
        /** Free-text note attached to a measurement. */
        val COMMENT: Key<String> = reg(
            Key(
                BUILTIN_PREFIX + "comment", R.string.measurement_type_comment,
                InputFieldType.TEXT, UnitType.NONE, NONE_UNIT, listOf(InputFieldType.TEXT),
                UnitType.NONE, 0xFFE0E0E0.toInt(), MeasurementTypeIcon.IC_COMMENT,
                false, true, true, false, false
            )
        )
        /** Table column for the measurement date — not a value handlers set (use [ScaleMeasurement.dateTime]). */
        val DATE: Key<java.util.Date> = reg(
            Key(
                BUILTIN_PREFIX + "date", R.string.measurement_type_date,
                InputFieldType.DATE, UnitType.NONE, NONE_UNIT, listOf(InputFieldType.DATE),
                UnitType.NONE, 0xFF9E9E9E.toInt(), MeasurementTypeIcon.IC_DATE,
                false, true, false, false, false
            )
        )
        /** Table column for the measurement time — not a value handlers set (use [ScaleMeasurement.dateTime]). */
        val TIME: Key<java.util.Date> = reg(
            Key(
                BUILTIN_PREFIX + "time", R.string.measurement_type_time,
                InputFieldType.TIME, UnitType.NONE, NONE_UNIT, listOf(InputFieldType.TIME),
                UnitType.NONE, 0xFF757575.toInt(), MeasurementTypeIcon.IC_TIME,
                false, true, false, false, false
            )
        )
        /** Table column for the user name — carries no value. */
        val USER: Key<Unit> = reg(
            Key(
                BUILTIN_PREFIX + "user", R.string.measurement_type_user,
                InputFieldType.USER, UnitType.NONE, NONE_UNIT, listOf(InputFieldType.USER),
                UnitType.NONE, 0xFF90A4AE.toInt(), MeasurementTypeIcon.IC_USER,
                false, true, false, false, false
            )
        )

        /** All predefined keys, in canonical display order. */
        val allKeys: List<Key<*>> = ordered.toList()

        private val index: Map<String, Key<*>> = allKeys.associateBy { it.identity }

        /** The predefined key behind [identity], or null for `ble.*`/`user.*` identities. */
        fun keyOf(identity: String): Key<*>? = index[identity]

        /**
         * A quantity a scale handler contributes, e.g. a segmental body-fat percentage.
         * Declare it as a constant in the handler's companion object; prefer a semantic
         * path a second scale reporting the same quantity can reuse
         * (`"segmental.fat.left_arm"`) over a vendor-bound one, so the user's history
         * survives switching scales. Everything besides the identity only seeds the type
         * on first use — the user may change all of it afterwards.
         *
         * The factory picks the delivery unit: [devicePercent] keys take [Percent] values,
         * [deviceKg] takes [Kg], [deviceFloat] a plain dimensionless [Float].
         */
        fun devicePercent(
            path: String,
            @StringRes nameResId: Int,
            icon: MeasurementTypeIcon = MeasurementTypeIcon.IC_DEFAULT,
            color: Int = DEVICE_DEFAULT_COLOR,
            enabled: Boolean = true,
            pinned: Boolean = false,
            rightAxis: Boolean = false,
            isInternal: Boolean = false
        ): Key<Percent> = deviceFloatKey(path, nameResId, UnitType.PERCENT, icon, color, enabled, pinned, rightAxis, isInternal)

        /** See [devicePercent]; raw values arrive as [Kg]. */
        fun deviceKg(
            path: String,
            @StringRes nameResId: Int,
            icon: MeasurementTypeIcon = MeasurementTypeIcon.IC_DEFAULT,
            color: Int = DEVICE_DEFAULT_COLOR,
            enabled: Boolean = true,
            pinned: Boolean = false,
            rightAxis: Boolean = false,
            isInternal: Boolean = false
        ): Key<Kg> = deviceFloatKey(path, nameResId, UnitType.KG, icon, color, enabled, pinned, rightAxis, isInternal)

        /** See [devicePercent]; a dimensionless decimal (score, index, ratio). */
        fun deviceFloat(
            path: String,
            @StringRes nameResId: Int,
            icon: MeasurementTypeIcon = MeasurementTypeIcon.IC_DEFAULT,
            color: Int = DEVICE_DEFAULT_COLOR,
            enabled: Boolean = true,
            pinned: Boolean = false,
            rightAxis: Boolean = false,
            isInternal: Boolean = false
        ): Key<Float> = deviceFloatKey(path, nameResId, UnitType.NONE, icon, color, enabled, pinned, rightAxis, isInternal)

        private fun <T : Any> deviceFloatKey(
            path: String, @StringRes nameResId: Int, wireUnit: UnitType,
            icon: MeasurementTypeIcon, color: Int, enabled: Boolean, pinned: Boolean,
            rightAxis: Boolean, isInternal: Boolean
        ): Key<T> = Key(
            DEVICE_PREFIX + path, nameResId, InputFieldType.FLOAT, wireUnit,
            wireUnit.convertibleUnits(), listOf(InputFieldType.FLOAT),
            wireUnit, color, icon, false, enabled, pinned, rightAxis, isInternal
        )

        /** A whole number a scale handler contributes, e.g. a count or a score. */
        fun deviceInt(
            path: String,
            @StringRes nameResId: Int,
            wireUnit: UnitType = UnitType.NONE,
            icon: MeasurementTypeIcon = MeasurementTypeIcon.IC_DEFAULT,
            color: Int = DEVICE_DEFAULT_COLOR,
            enabled: Boolean = true,
            pinned: Boolean = false,
            isInternal: Boolean = false
        ): Key<Int> = Key(
            DEVICE_PREFIX + path, nameResId, InputFieldType.INT, wireUnit,
            listOf(wireUnit), listOf(InputFieldType.INT),
            wireUnit, color, icon, false, enabled, pinned, false, isInternal
        )

        /** Free text a scale handler contributes, e.g. a body-type classification code. */
        fun deviceText(
            path: String,
            @StringRes nameResId: Int,
            icon: MeasurementTypeIcon = MeasurementTypeIcon.IC_DEFAULT,
            color: Int = DEVICE_DEFAULT_COLOR,
            enabled: Boolean = true,
            pinned: Boolean = false,
            isInternal: Boolean = false
        ): Key<String> = Key(
            DEVICE_PREFIX + path, nameResId, InputFieldType.TEXT, UnitType.NONE,
            listOf(UnitType.NONE), listOf(InputFieldType.TEXT),
            UnitType.NONE, color, icon, false, enabled, pinned, false, isInternal
        )

        /** Fresh rows for every predefined key, in canonical display order — the seed set. */
        fun seedRows(): List<MeasurementType> = allKeys.map { key ->
            MeasurementType(
                identity = key.identity,
                name = null,
                color = key.defaultColor,
                icon = key.defaultIcon,
                unit = key.defaultUnit,
                inputType = key.inputType,
                isDerived = key.isDerived,
                isEnabled = key.defaultEnabled,
                isPinned = key.defaultPinned,
                isOnRightYAxis = key.defaultOnRightYAxis,
                isInternal = key.isInternal
            )
        }

        /** Strips the namespace prefix and renders the rest as an UPPER_SNAKE column key. */
        fun identityColumnKey(identity: String): String =
            identity.substringAfter('.').replace('.', '_').uppercase()

        /** Reserved column headers a user-created type must never shadow. */
        private val BUILT_IN_COLUMN_KEYS: Set<String> =
            allKeys.map { identityColumnKey(it.identity) }.toSet()

        /**
         * Builds the `user.*` identity for a user-created type from its display name.
         * Uniqueness is checked on the *derived column key*, so a type called "Weight"
         * cannot end up sharing the predefined `WEIGHT` column, and "Schritte" /
         * "schritte" cannot collide either; a taken key gets a `_2`, `_3`, … suffix.
         *
         * @param takenColumnKeys column keys of every other type, compared case-insensitively.
         */
        fun userIdentityFor(name: String?, takenColumnKeys: Set<String>): String {
            val slug = slugify(name)
            val reserved = BUILT_IN_COLUMN_KEYS + takenColumnKeys.map { it.uppercase() }

            var candidate = slug
            var suffix = 1
            while (identityColumnKey(USER_PREFIX + candidate) in reserved) {
                suffix++
                candidate = "${slug}_$suffix"
            }
            return USER_PREFIX + candidate
        }

        /**
         * The `user.*` identity for a CSV column the user typed in themselves, or null
         * when they may not have it — blank, unslugifiable, or already taken. Unlike
         * [userIdentityFor] nothing is numbered away: the user asked for that exact
         * column, so the editor shows an error instead of silently working around it.
         *
         * @param takenColumnKeys every other type's column key; the edited type's own
         *   excluded, so it can be left as it is.
         */
        fun userIdentityFrom(columnKey: String, takenColumnKeys: Set<String>): String? {
            val slug = slugify(columnKey)
            if (columnKey.isBlank() || slug == UNNAMED_SLUG) return null

            val identity = USER_PREFIX + slug
            val reserved = BUILT_IN_COLUMN_KEYS + takenColumnKeys.map { it.uppercase() }
            return identity.takeIf { identityColumnKey(it) !in reserved }
        }

        /** Not "custom": that would derive the CUSTOM column key. */
        private const val UNNAMED_SLUG = "unnamed"

        /** lowercase, non-alphanumeric to '_', collapsed and trimmed. Unicode aware. */
        private fun slugify(text: String?): String = text.orEmpty()
            .lowercase()
            .map { if (it.isLetterOrDigit()) it else '_' }
            .joinToString("")
            .replace(Regex("_+"), "_")
            .trim('_')
            .ifBlank { UNNAMED_SLUG }
    }
}
