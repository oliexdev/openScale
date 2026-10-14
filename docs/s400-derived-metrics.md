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

The bundled `app/src/test/resources/s400/derived-v235.tsv` contains 276 calculation-only numeric response rows obtained using authorized synthetic requests. No Xiaomi account identifiers, credentials, network headers, device IDs, application caches or decrypted arbitrary responses are included.

`S400DerivedMetricsTest` is a JUnit 4 suite. It exercises the recorded responses, missing components, water conversion, fractional skeletal values, invalid required inputs, malformed fixtures and empty fixtures. `S400DerivedMetricsFixtureKt` also exposes a standalone JVM entry point accepting one TSV path; every row must have 15 columns.

The sources were compiled and exercised with the cached Kotlin JVM compiler and JUnit 4.13.2, returning:

```
checked=276 failures=0
OK (9 tests)
```

This is JVM verification of these sources, not an Android APK build, phone installation, or verification of the upstream BIA formulas. Input fields copied into outputs are not recovered formulas.

A separately frozen empirical body-fat candidate matched 32/36 new holdout cases exactly (maximum difference 0.1 percentage point). Its mass-free-fat prediction matched 33/36 with the correct rounding order. Auxiliary raw regressions failed their full tolerance checks. Those coefficients are intentionally absent from production code.
