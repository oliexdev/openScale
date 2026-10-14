# Análisis de Ingeniería Inversa: Xiaomi Mi Scale 2 → libBodyfat.so (Holtek)

## Resumen Ejecutivo

La báscula Xiaomi Mi Scale 2 utiliza el algoritmo propietario de **Holtek Semiconductor** (`libBodyfat.so` / `HTBodyfat`), no un algoritmo propio de Xiaomi. La fabricación del hardware es de Huami. Este documento documenta las **fórmulas exactas** extraídas del binario nativo ARM64 y las compara con la implementación de OpenScale (`MiScaleLib.kt` y `BodyMiScaleLib.kt`).

Se identificaron **3 discrepancias críticas** que explican las diferencias en las métricas reportadas:

| # | Métrica | Discrepancia | Causa Raíz |
|---|---------|-------------|------------|
| 1 | Grasa Visceral | OpenScale: 8 vs Xiaomi: 15.7 | **Bug en MiScaleLib.kt**: resta `age` en lugar de `10.5` |
| 2 | Músculo % | OpenScale: 43.8% vs Xiaomi: 72% | **Fórmula completamente distinta**: OpenScale usa BIA directa, Holtek deriva de peso-grasa-hueso |
| 3 | BMR | Diferencia variable | **OpenScale no usa impedancia** (Schofield), Holtek sí la incluye |
| 4 | Grasa Corporal % | 23.9% vs 18.6% | **Clamp diferente**: Holtek clamp(5,75), OpenScale cap en 63→75 sin mínimo |
| 5 | Masa Ósea | Ligera diferencia | **Clamp diferente**: Holtek usa rangos por peso, OpenScale usa cap fijo en 8.0 |

---

## 1. Arquitectura del Sistema

### 1.1 Flujo de Comunicación BLE

```
Mi Scale 2 (Holtek BLE)
    │
    ├─ Service UUID: 0x181D (Weight Scale Service)
    │   ├─ Characteristic 0x2A9D (Weight Measurement) → notificaciones
    │   └─ Characteristic 0x2A9C (Weight Feature) → lectura
    │
    ├─ Service UUID: 0x181B (Body Composition Service)  
    │   ├─ Characteristic 0x2A9C (Body Composition Feature)
    │   └─ Characteristic 0x2A9D (Body Composition Measurement) → impedancia
    │
    └─ SDK Bluetooth: com.inuker.bluetooth.library
        └─ Orquestador: Huami scale bridge
            └─ JNI → libBodyfat.so (HTBodyfat)
```

### 1.2 Mapeo de Funciones Nativas (JNI)

El wrapper Java `_m_j/x2b.java` (ofuscado) en `classes56.dex` vincula cada método nativo con su métrica:

| Método Nativo | Campo en x2b | Métrica | Tipo de Retorno |
|---------------|-------------|---------|-----------------|
| `NN(w, z, sex, age, h)` | — | Entry point / validación | int (0=OK) |
| `CC()` | OooOOO0 | **Grasa Corporal %** | double |
| `HH()` | OooOOO | **Agua %** | double |
| `DD()` | OooOO0o | **Masa Ósea (kg)** | double |
| `FF()` | OooOOOO | **Músculo (masa/peso)** | double |
| `GG()` | OooOO0O | **Grasa Visceral (nivel)** | int |
| `BB()` | OooOO0 | **BMR (kcal)** | double→int |
| `EE()` | OooO | **BMI** | double |
| `AA()` | — | Retorna _bodyfatPercentage | double |
| `QQ()` | — | Peso Ideal | double |
| `RR()` | — | BMR Standard | double |
| `II/JJ/KK/LL/MM` | — | Arrays de rating (bounds) | void |

### 1.3 Variables Globales (libBodyfat.so .data section)

| Símbolo | Dirección | Tipo | Descripción |
|---------|-----------|------|-------------|
| `_weightKg` | 0x14360 | double | Peso (kg) |
| `_heightCm` | 0x14358 | double | Altura (cm) |
| `_zTwoLegs` | 0x14348 | int | Impedancia raw (BLE) |
| `_sex` | 0x1434c | int | 1=Masculino, 0=Femenino |
| `_age` | 0x14350 | int | Edad (años) |
| `_bodyfatPercentage` | 0x14340 | double | Grasa % (resultado de CC) |
| `_boneKg` | 0x14338 | double | Masa ósea (resultado de DD) |
| `_bodyfatRatingList` | 0x142d0 | struct[4] | Bounds rating grasa (runtime) |
| `_muscleRatingList` | 0x142b0 | struct[2] | Bounds rating músculo (runtime) |
| `_waterRatingList` | 0x142c0 | struct[2] | Bounds rating agua (runtime) |
| `_boneRatingList` | 0x142f0 | struct[2] | Bounds rating hueso (runtime) |
| `_VFALRatingList` | 0x14300 | struct[2] | Bounds rating visceral (runtime) |
| `_BMRRatingList` | 0x14310 | struct[1] | Bounds rating BMR (runtime) |
| `_BMIRatingList` | 0x14320 | struct[3] | Bounds rating BMI (runtime) |

---

## 2. Fórmulas Reconstruidas

### 2.1 Validación — `NN(weight_raw, impedance_raw, sex, age, height)`

```
INPUT: weight (kg), impedance_raw (int from BLE), sex (int), age (int), height (cm)

impedance = impedance_raw / 1200.0    // conversión a ohms

Validaciones:
  height:   50 <= h <= 3000     → error code 1
  age:      6 <= age <= 99      → error code 2  
  weight:   10 < w < 200        → error code 3
  impedance: 90 < z <= 220      → error code 4

Almacena globals:
  _weightKg = weight
  _zTwoLegs = impedance_raw
  _sex = sex
  _age = age
  _heightCm = height

return 0 si todo OK
```

### 2.2 Función Auxiliar — `getlbmCoefficient()`

```python
lbmCoeff = (height * 9.058 / 100) * (height / 100) \
         + weight * 0.32 \
         + 12.226 \
         - impedance * 0.0068 \
         - age * 0.0542
```

> **Estado OpenScale**: ✅ Idéntico en `MiScaleLib.kt` `getLBMCoefficient()`

### 2.3 Función Auxiliar — `checkValueOverflow(value, min, max)`

```python
if value < min: return min
if value > max: return max
return value
# Equivalente a: clamp(value, min, max)
```

### 2.4 Grasa Corporal % — `CC()`

```python
lbmCoeff = getlbmCoefficient()

# Coeficiente de sustracción según sexo y edad
if sex == MALE:
    lbmSub = 0.8
elif sex == FEMALE:
    if age <= 49:
        lbmSub = 9.25
    else:
        lbmSub = 7.25

# Ajuste por peso y altura
coeff = 1.0
if sex == FEMALE:
    if weight < 50:
        coeff = 1.02
    if weight > 60:
        coeff = 0.96
    if height > 160:
        coeff *= 1.03
elif sex == MALE:
    if weight < 61:
        coeff = 0.98

bodyFat = (1.0 - ((lbmCoeff - lbmSub) * coeff) / weight) * 100.0
bodyFat = clamp(bodyFat, 5.0, 75.0)    # Holtek: clamp 5-75
```

> **⚠️ CORRECCIÓN MAYOR (2026-10-14):** ver nota equivalente en la sección de Grasa Visceral
> más abajo — este binario completo no corresponde al algoritmo real del S400 (validado contra
> 356 pares reales del oráculo; ver `docs/s400_v235_body_fat_recovery.md`). El punto específico
> de anidamiento de altura señalado originalmente aquí puede seguir siendo real *para este
> binario*, pero es irrelevante para efectos de paridad con el S400.

> **Discrepancia con OpenScale**:
> - OpenScale: `if bodyFat > 63: bodyFat = 75` (cap en 63, sin mínimo)
> - Holtek: `clamp(bodyFat, 5.0, 75.0)` (min=5, max=75)
> - **Impacto**: Si el bodyFat calculado es < 5%, Holtek reporta 5% mientras que OpenScale reporta el valor real (potencialmente negativo o muy bajo). Si está entre 63 y 75, ambos coinciden.

### 2.5 Agua % — `HH()`

```python
water = (100.0 - bodyFatPercent) * 0.7
if water <= 50:
    water *= 1.02
else:
    water *= 0.98

# Rating bounds
if water <= 50:
    rating = (35.0, 53.0)   # rango bajo
else:
    rating = (53.0, 67.0)   # rango alto

water = clamp(water, 35.0, 75.0)
```

> **Estado OpenScale**: ✅ Fórmula idéntica. Diferencia menor: Holtek aplica clamp(35, 75), OpenScale no tiene clamp explícito.

### 2.6 Masa Ósea (kg) — `DD()`

```python
lbmCoeff = getlbmCoefficient()

if sex == FEMALE:
    base = 0.245691014
else:  # MALE
    base = 0.18016894

boneMass = (lbmCoeff * 0.05158) - base
# Equivalente a: (base - lbmCoeff * 0.05158) * -1

if boneMass > 2.2:
    boneMass += 0.1
else:
    boneMass -= 0.1

# Rating bounds dependientes de peso y sexo
if sex == FEMALE:
    if weight < 45:   bounds = (0.8, 2.8)
    elif weight < 60: bounds = (1.2, 3.2)
    elif weight < 75: bounds = (1.5, 3.5)
    else:             bounds = (0.5, 4.2)   # Notar: min baja a 0.5
else:  # MALE
    if weight < 60:   bounds = (1.5, 3.5)
    else:             bounds = (1.9, 3.9)

boneMass = clamp(boneMass, bounds.min, bounds.max)
```

> **Discrepancia con OpenScale**:
> - OpenScale usa `if boneMass > 5.1 (F) / 5.2 (M): boneMass = 8.0` (cap fijo)
> - Holtek usa rangos dinámicos por peso → más granular y preciso
> - **Impacto**: Para personas con peso > 75kg (mujer), Holtek permite hasta 4.2kg, OpenScale caparía incorrectamente en 8.0.

### 2.7 Músculo — `FF()`

```python
# NOTA: FF deriva el músculo de otras métricas, NO usa una fórmula BIA directa
muscle_kg = weight * (1.0 - bodyFatPercent / 100.0) - boneMass
#          = weight - fatMass - boneMass

# Rating bounds por sexo y altura
if sex == FEMALE:
    if height < 160:   bounds = (29.1, 34.7)
    elif height < 170: bounds = (32.9, 37.5)
    else:              bounds = (36.5, 42.5)
else:  # MALE
    if height < 160:   bounds = (38.5, 46.5)
    elif height < 170: bounds = (44.0, 52.4)
    else:              bounds = (49.5, 59.4)

muscle_kg = clamp(muscle_kg, 10.0, 120.0)
```

> **⚠️ DISCREPANCIA CRÍTICA con OpenScale**:
> - **Holtek**: `muscle = weight - fatMass - boneMass` (derivado, **sin impedancia**)
> - **OpenScale**: `smmKg = 0.401 * (h²/impedance) + 3.825*sex - 0.071*age + 5.102` (BIA directa)
> - **OpenScale**: `muscle% = (smmKg / weight) * 100`, clamped (10, 60)
> - **Impacto**: OpenScale reporta ~43.8% vs Xiaomi ~72% → **diferencia masiva**
> - La fórmula de Holtek es una resta simple (peso sin grasa menos hueso), mientras que OpenScale intenta una estimación directa de masa muscular esquelética (SMM) mediante BIA, que es un enfoque completamente diferente.

### 2.8 Grasa Visceral — `GG()`

```python
if sex == FEMALE:
    if weight > (height * 0.5 - 13):
        subsubcalc = height * 1.45 + height * 0.1158 * height - 120
        subcalc = weight * 500 / subsubcalc
        vfal = subcalc - 6 + age * 0.07
    else:
        subcalc = 0.691 + height * (-0.0024) + height * (-0.0024)
        vfal = ((height * 0.027 - subcalc * weight) * -1) + age * 0.07 - 10.5
        #                                                       ^^^^
        #                                          Holtek resta 10.5, NO age

elif sex == MALE:
    if height < (weight * 1.6 + 63):
        subcalc = (height * 0.4 - height * (height * 0.0826)) * -1
        vfal = (weight * 305 / (subcalc + 48)) - 2.9 + age * 0.15
    else:
        subcalc = 0.765 + height * (-0.0015)
        vfal = ((height * 0.143 - weight * subcalc) * -1) + age * 0.15 - 5

vfal = clamp(vfal, 10, 15)
```

> **⚠️ CORRECCIÓN MAYOR (2026-10-14):** validación cruzada contra los 356 pares input→output
> reales capturados por el oráculo en vivo (`s400_parity_v2/`) demuestra que **este binario
> `libBodyfat.so` NO es el algoritmo que usa el S400 real**. Prueba decisiva: la fórmula BMR
> de este documento (Holtek `BB()`, con impedancia) da 0/356 coincidencias exactas contra el
> oráculo; el BMR real del S400 es Katch-McArdle puro (`370 + 21.6×FFM`, sin impedancia),
> 356/356 exacto. El esquema de parámetros del oráculo real (`current_impedance:[r1,r2]`,
> `electrodes_number`, `res_mapping_switch`) tampoco coincide con la firma `HTBodyfat.NN`
> de impedancia única. Conclusión: el algoritmo real "versión 235" del S400 se calcula
> server-side (`/eco/scale/bcalc` es una llamada de red, no JNI local); este documento
> describe correctamente un binario real pero de un dispositivo Xiaomi distinto (probablemente
> Mi Body Composition Scale 2), no del S400. Ver `docs/s400_v235_body_fat_recovery.md` en este
> mismo repo para el método de verificación completo, la fórmula de grasa corporal recuperada
> empíricamente, y su integración en `S400BodyFatV235.kt`. La nota de clamp (10,15) de abajo
> puede seguir siendo correcta *para este binario* — no se tocó esa observación.

> **⚠️ BUG CRÍTICO en OpenScale MiScaleLib.kt**:
> - `MiScaleLib.kt` rama else femenina: `vfal = ... + age * 0.07 - age` ← **BUG**
> - `BodyMiScaleLib.kt`: `vfal = ... + age * 0.07 - 10.5` ← **CORRECTO**
> - Holtek: `vfal = ... + age * 0.07 - 10.5` ← **Confirmado del binario**
> - **Impacto**: Para una mujer de 30 años, OpenScale `MiScaleLib` resta 30 en lugar de 10.5 → diferencia de 19.5 puntos en grasa visceral. Esto explica el reporte de 8 vs 15.7.
> - **Fix**: Cambiar `- age` por `- 10.5` en `MiScaleLib.kt`, o usar `BodyMiScaleLib.kt` que ya está correcto.

### 2.9 BMR (Metabolismo Basal) — `BB()`

```python
if sex == MALE:
    bmr = weight * 14.916 + 877.8 - impedance * 0.726 - age * 8.976
else:  # FEMALE
    bmr = weight * 10.2036 + 864.6 - impedance * 0.39336 - age * 6.204

bmr = clamp(bmr, 500.0, 10000.0)

# BMR Rating Standard (coeficiente * peso)
if sex == FEMALE:
    if age <= 12: bmrStd = 34 * weight
    elif age <= 15: bmrStd = 29 * weight
    elif age <= 17: bmrStd = 24 * weight
    elif age <= 29: bmrStd = 22 * weight
    elif age <= 49: bmrStd = 23 * weight
    else: bmrStd = 20 * weight
else:  # MALE
    if age <= 12: bmrStd = 36 * weight
    elif age <= 15: bmrStd = 30 * weight
    elif age <= 17: bmrStd = 26 * weight
    elif age <= 29: bmrStd = 23 * weight
    elif age <= 49: bmrStd = 21 * weight
    else: bmrStd = 19 * weight
```

> **Discrepancia con OpenScale**:
> - OpenScale usa ecuaciones de **Schofield** (sin impedancia):
>   - Male 18-30: `15.057 * weight + 692.2`
>   - Male 30-60: `11.472 * weight + 873.1`
>   - Female 18-30: `14.818 * weight + 486.6`
>   - Female 30-60: `8.126 * weight + 845.6`
> - Holtek **incluye impedancia** en el BMR → más preciso para composición corporal
> - Clamp: Holtek (500, 10000) vs OpenScale (500, 5000)

### 2.10 BMI — `EE()`

```python
bmi = weight / (height / 100.0) ** 2
bmi = clamp(bmi, 10.0, 90.0)

# Rating bounds
underweight = 18.5
normal = 25.0
overweight = 30.0
```

> **Estado OpenScale**: ✅ Idéntico

### 2.11 Tablas de Rating de Grasa Corporal

Extraídas de `.data` section (21 entradas por sexo, indexadas por edad):

#### BodyfatRatingTableFemale (0x14010, 336 bytes = 21 × 16 bytes)

| Índice | Edad aprox | thin_max | normal_max | over_max | obese_max |
|--------|-----------|----------|------------|----------|-----------|
| 0 | 6-7 | 17 | 28 | 37 | 42 |
| 1 | 8-9 | 17 | 28 | 37 | 42 |
| ... | ... | ... | ... | ... | ... |
| 16 | ~38 | 19 | 28 | 37 | 41 |
| 17 | ~40 | 20 | 28 | 37 | 41 |
| 18 | ~42 | 21 | 28 | 35 | 40 |
| 19 | ~44 | 22 | 29 | 36 | 41 |
| 20 | 46+ | 23 | 30 | 37 | 42 |

#### BodyfatRatingTableMale (0x14160, 336 bytes = 21 × 16 bytes)

| Índice | Edad aprox | thin_max | normal_max | over_max | obese_max |
|--------|-----------|----------|------------|----------|-----------|
| 0 | 6-7 | 5 | 16 | 24 | 28 |
| 1 | 8-9 | 5 | 16 | 24 | 28 |
| ... | ... | ... | ... | ... | ... |
| 16 | ~38 | 8 | 16 | 24 | 28 |
| 17 | ~40 | 9 | 16 | 23 | 28 |
| 18 | ~42 | 11 | 17 | 22 | 27 |
| 19 | ~44 | 12 | 16 | 23 | 28 |
| 20 | 46+ | 14 | 16 | 25 | 30 |

> **Nota**: OpenScale no implementa estas tablas de rating. Las categorías (thin/normal/over/obese) son específicas del firmware Holtek.

---

## 3. Resumen de Discrepancias y Fixes Propuestos para OpenScale

### Fix 1: Grasa Visceral (CRÍTICO — Bug confirmado)

**Archivo**: `MiScaleLib.kt`  
**Problema**: En la rama else femenina, se resta `age` en lugar de `10.5`  
**Línea afectada**: `visceralFat = ((height * 0.027 - subcalc * weight) * -1) + age * 0.07 - age`  
**Fix**: Cambiar `- age` por `- 10.5`  
**Verificación**: `BodyMiScaleLib.kt` ya tiene el valor correcto (`- 10.5`)

### Fix 2: Músculo % (Diferencia arquitectónica)

**Archivo**: `MiScaleLib.kt`  
**Problema**: OpenScale usa fórmula BIA directa (`0.401 * h²/R`) vs Holtek que deriva de `weight - fat - bone`  
**Impacto**: 43.8% vs 72% — diferencia masiva  
**Fix propuesto**: Reemplazar `getMuscle()` con:
```kotlin
fun getMuscle(bodyFatPercent: Double, weight: Double, boneMass: Double): Double {
    val muscleKg = weight * (1.0 - bodyFatPercent / 100.0) - boneMass
    return clamp(muscleKg, 10.0, 120.0)
    // NOTA: Holtek retorna masa en kg, OpenScale retorna porcentaje
    // Si se quiere porcentaje: muscleKg / weight * 100
}
```

### Fix 3: BMR (Diferencia de modelo)

**Archivo**: `MiScaleLib.kt` / `BodyMiScaleLib.kt`  
**Problema**: OpenScale usa Schofield (sin impedancia), Holtek incluye impedancia  
**Fix propuesto**: Añadir fórmula BMR de Holtek como alternativa:
```kotlin
fun getBMRHoltek(sex: Int, weight: Double, impedance: Double, age: Int): Double {
    val bmr = if (sex == 1) {
        weight * 14.916 + 877.8 - impedance * 0.726 - age * 8.976
    } else {
        weight * 10.2036 + 864.6 - impedance * 0.39336 - age * 6.204
    }
    return clamp(bmr, 500.0, 10000.0)
}
```

### Fix 4: Body Fat % Clamp

**Archivo**: `MiScaleLib.kt`  
**Problema**: `if bodyFat > 63: bodyFat = 75` debería ser `clamp(bodyFat, 5, 75)`  
**Fix**: 
```kotlin
bodyFat = max(5.0, min(75.0, bodyFat))
```

### Fix 5: Masa Ósea Clamp

**Archivo**: `MiScaleLib.kt`  
**Problema**: Cap fijo en 8.0, debería usar rangos dinámicos por peso  
**Fix**: Implementar tabla de bounds por peso y sexo como se documenta arriba

---

## 4. Metodología de Extracción

1. **APK**: Xiaomi Home (283MB) — decompilación fallida por RAM con jadx completo
2. **DEX selectivo**: `classes56.dex` decompilado exitosamente con jadx
3. **Wrapper identificado**: `_m_j/x2b.java` — mapeo de métodos nativos a métricas
4. **Binario nativo**: `lib/arm64-v8a/libBodyfat.so` (Holtek) — 8.6KB
5. **Desensamblado**: Capstone (Python) para ARM64 little-endian
6. **Constantes**: Extracción de literals desde `.text` (adrp + ldr pattern)
7. **Rating tables**: Lectura directa de `.data` section (structs de 4 × int32)
8. **Comparación**: OpenScale repo (`MiScaleLib.kt`, `BodyMiScaleLib.kt`)
9. **Validación**: Coeficientes LBM confirmados idénticos entre ambos

---

## 5. Archivos de Referencia

| Archivo | Fuente | Propósito |
|---------|--------|-----------|
| `libBodyfat.so` | APK Xiaomi Home | Binario nativo Holtek (algoritmo real) |
| `x2b.java` | classes56.dex decompiled | Wrapper JNI → mapeo de métricas |
| `HTBodyfat.java` | classes56.dex decompiled | Declaración de métodos nativos |
| `MiScaleLib.kt` | OpenScale repo | Implementación actual (con bug VFAL) |
| `BodyMiScaleLib.kt` | OpenScale repo | Implementación alternativa (VFAL correcto) |

---

*Documento generado el 2026-10-13 a partir del análisis de ingeniería inversa de libBodyfat.so (ARM64) y comparación con OpenScale.*