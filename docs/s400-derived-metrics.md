# Xiaomi S400 v235: derived arithmetic only

`S400DerivedMetrics` is a pure Kotlin helper. It is intentionally **not wired into the Bluetooth calculator** while upstream BIA formulas remain unverified. Do not describe it as a complete Xiaomi-compatible offline calculator.

## Inputs and rounding

Height is in cm, weight and component masses in kg. Components are the displayed body-fat percentage and optional bone mass, water percentage and skeletal muscle mass. Missing upstream fields stay null. Lean mass is never substituted for skeletal muscle.

The observed sequence is:

```
fatPct = round1(bodyFatPct)
bfm = round1(weight * fatPct / 100)
ffm = round1(weight - bfm)
bmr = round(370 + 21.6 * ffm)
slm = round1(ffm - bone)
waterKg = round1(weight * waterPct / 100)
```

These relations identify downstream arithmetic, not the impedance-to-component model. Fractional skeletal clamp values are passed through unchanged. Percentage-to-water-mass conversion must not be inverted from an already-rounded mass.

## Evidence and tests

The bundled `app/src/test/resources/s400/derived-v235.tsv` contains 356 calculation-only numeric response rows obtained using authorized synthetic requests. No Xiaomi account identifiers, credentials, network headers, device IDs, application caches or decrypted arbitrary responses are included.

`S400DerivedMetricsTest` is a JUnit 4 suite. It exercises the recorded responses, missing components, water conversion, fractional skeletal values, invalid required inputs, malformed fixtures and empty fixtures. `S400DerivedMetricsFixtureKt` also exposes a standalone JVM entry point accepting one TSV path; every row must have 15 columns.

The sources were compiled and exercised with the cached Kotlin JVM compiler and JUnit 4.13.2, returning:

```
checked=356 failures=0
OK (14 tests)
```

This is JVM verification of these sources, not an Android APK build, phone installation, or verification of the upstream BIA formulas. Input fields copied into outputs are not recovered formulas.

A separately frozen empirical body-fat candidate matched 32/36 new holdout cases exactly (maximum difference 0.1 percentage point). Its mass-free-fat prediction matched 33/36 with the correct rounding order. Auxiliary raw regressions failed their full tolerance checks. Those coefficients are intentionally absent from production code.

## New interval-fitted body-fat candidate

`S400BodyFatV235Experimental` is a separate, opt-in helper, **not used by the active Bluetooth calculator**. Its coefficients were inferred from rounded response intervals, not extracted from Xiaomi code. Exploratory fitting matched all 309 unique inputs available before the new holdout; a fresh 20-input holdout, with predictions saved before requests, matched BFP, FFM and BMR in 20/20 cases. No holdout refitting was performed.

The Kotlin port and downstream FFM/BMR were checked against 275 unique recorded inputs within the helper's validity fence, including the new 20-input holdout. `S400BodyFatV235ExperimentalTest` also checks the original reference and rejects unsupported ages, anthropometry and impedances.

The helper returns null outside height 148–194 cm, weight 48–105 kg, age 19–68 years, R50 450–780 ohm, and R250/R50 ratio 0.78–0.95. It accepts literal API integer units and converts them to Double. A validity fence is a restriction on use, not a claim that all combinations within it have been experimentally covered.

The empirical structure is:

```
latentFFM = c0 + cH*height_cm + cW*weight_kg + cA*age + cR*R50_ohm
BFP = round1(clamp(100 * (1 - latentFFM/weight_kg), 3, 75))
```

Coefficients differ by sex and are preserved in source and `s400-phase3-bfp-model.json`. They are not unique constants proven to be Xiaomi's original coefficients. Exact sampled agreement does not establish global parity or recover muscle, bone, water or metabolic age.

The new metabolic-age candidate matched 17/20 holdout cases and missed one by three years. It remains outside production. Height dependence was observed for the new profiles even though an earlier profile's height sweep was invariant; that earlier independence must not be extrapolated globally.

The extra authorization was exhausted with 80 issued requests and 80 responses. Numerical evidence is in `s400-phase3-validation.json`. An Android APK has not been built or installed as part of this verification.
