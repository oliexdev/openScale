# Xiaomi S400 "v235" Body Fat — Recovered by Empirical Fit Against the Real Server, Not Reverse Engineering

## TL;DR

The S400 does not compute body fat on-device or in the Xiaomi Home APK. It uploads raw
dual-frequency impedance to `POST /eco/scale/bcalc`; a Xiaomi/Yunmai server does the math and
returns the result. `lib/arm64-v8a/libBodyfat.so` ("Holtek") bundled in Xiaomi Home — previously
assumed to be the S400's algorithm and used as the basis for `S400BodyComposition.kt` — belongs to
a **different, older Xiaomi scale**. Using it for the S400 produces numbers that look plausible
but are measurably wrong.

This was proven, not assumed: the Holtek-derived BMR formula was checked against 356 real
`/eco/scale/bcalc` request/response pairs already captured in a prior session
(`s400_parity_v2/batch*_responses.json`, `training_responses.json`) and matched **0 of 356**
(errors of hundreds of kcal). `S400BodyComposition.kt` had already independently discovered this
for BMR and switched to Katch-McArdle — but still used the Holtek branch structure for body fat,
bone, muscle, water and visceral fat without the same verification.

Body fat was then recovered empirically from scratch (ordinary least squares, no inherited
coefficients) and lands exactly on **339 of 356** real server responses, with the remaining 17
off by exactly 0.1 percentage point — a rounding-boundary artifact, not a model error (verified
per-case, see below). This is implemented in `S400BodyFatV235.kt` and wired into
`MiScaleS400Handler.kt` for the `BODY_FAT` measurement field.

## Why this matters for Xiaomi/OpenScale parity

OpenScale's stated goal for the S400 is numeric parity with the Xiaomi Home app on the same
weigh-in. A formula that is structurally wrong (wrong input variable, wrong units, wrong
coefficients) will diverge from Xiaomi's displayed value by an unpredictable amount depending on
the user's profile — sometimes close, sometimes off by many points. `S400BodyFatV235` is fit
*directly* against what the real server returns, so by construction it reproduces the real
Xiaomi Home body-fat reading within a documented domain and tolerance.

## Method

1. **Did not trust any pre-existing documentation or code comment claiming "Holtek parity."**
   Every claim was independently re-derived from the 356 raw server responses on disk.
2. Isolated BMR as a clean, impedance-independent test: Holtek's `BB()` formula vs. the real
   server's `bmr` field. 0/356 matched Holtek; 356/356 matched Katch-McArdle
   (`370 + 21.6 × FFM`), already present in `S400BodyComposition.kt`. This proved the Holtek
   binary is the wrong source for this device's algorithm, not merely imprecise.
3. For body fat, tried to validate the *existing* Holtek-derived branch logic (nested vs.
   independent conditionals, impedance transforms) against controlled sweeps in the dataset
   (fixed height/weight/impedance, only age varying; fixed age/weight/impedance, only height
   varying). The implied age coefficient from the data (~0.0184/year) didn't match Holtek's
   0.0542/year by any consistent transform — ruling out "right structure, wrong constant."
4. Re-derived from first principles: fit `latent_FFM(h, w, age, R_high) = c0 + c1·h + c2·w +
   c3·age + c4·R_high` by ordinary least squares, separately per sex, using every non-clamped
   training sample (`bfp` strictly between 3.0 and 75.0 — the clamp boundary samples are not
   invertible and were excluded from the *fit*, though they are included in the *validation*
   below). `R_high` is the **single, high-frequency (Packet A) impedance reading** in ohms — no
   parallel-combination or frequency-blending transform. That transform (seen in
   `S400BodyComposition.kt`, attributed to a "0.04% match on one real measurement") was never
   independently verified against the full dataset and is not used here.
5. Validated with a proper train/holdout split I generated myself (not reusing any split from
   prior work): fit on 80% of non-clamped rows, predict the held-out 20%, round to one decimal
   exactly as the fixture does. Result: 34/34 (female) and 33/35 (male) exact; the 2 misses are
   0.1pp rounding-boundary cases, confirmed by hand.
6. Re-fit on the **full** non-clamped dataset (171 female + 178 male rows) for the shipped
   coefficients, then validated end-to-end against **all 356** samples including the
   clamp-boundary ones (which exercise the `coerceIn(3.0, 75.0)` floor/ceiling): 339/356 exact,
   17 at exactly 0.1pp off, 0 at ≥0.2pp off.
7. Cross-checked independently against `android_app/app/src/test/resources/s400/body-fat-v235.tsv`
   (275 rows, a subset of the same 356 samples formatted for JVM testing, already present in this
   repo from prior work): 263/275 exact, 12 within the same 0.1pp rounding tolerance, **0 real
   mismatches** — same pattern, confirming the fit is not an artifact of how I partitioned data.
8. Compiled and ran the actual Kotlin (`S400BodyFatV235.kt` + `S400BodyFatV235Test.kt`) with the
   Kotlin 2.4.20 compiler + JUnit 4.13.2 (no Android Gradle Plugin / SDK required — this is a
   pure-Kotlin, JVM-only object) to confirm the numbers aren't a Python-only simulation:
   `OK (6 tests)`.

## What is verified vs. what is not

| Metric | Status |
|---|---|
| Body fat % | **Verified**: 339/356 exact against real server responses, independently fit and holdout-tested |
| BMR | Already fixed in a prior session (Katch-McArdle); re-confirmed here as a side effect |
| SLM = FFM − BMC | **Verified as an exact algebraic identity**, 356/356 — not a model, arithmetic |
| Bone mass (BMC) | Attempted affine fit: only ~27/35 in holdout at 0.15 kg tolerance — not solved |
| Skeletal muscle (SMM) | **Not affine** in (height, weight, age, R_high) — a controlled sweep varying only R_high (all else fixed) shows SMM rising then falling (non-monotonic), which no linear model can capture. Needs a different functional form (likely rational in both impedance bands, R_high and R_low, given the S400's dual-frequency hardware) and was not pursued further this session |
| Body water %, Visceral fat level | Not attempted with the same rigor this session; do not assume they are correct in `S400BodyComposition.kt` |

**Do not** treat hueso/músculo/agua/VFAL as validated just because body fat now is. They still run
through the unverified Holtek branch in `S400BodyComposition.kt` and may be wrong in the same way
BMR and body fat were.

## Files

- `android_app/app/src/main/java/com/health/openscale/core/bluetooth/libs/S400BodyFatV235.kt` —
  the verified model (new).
- `android_app/app/src/test/java/com/health/openscale/core/bluetooth/libs/S400BodyFatV235Test.kt` —
  JUnit test against the real fixture (new).
- `android_app/app/src/test/resources/s400/body-fat-v235.tsv` — pre-existing fixture, reused
  as-is (275 real server samples, verified to be a faithful subset of the 356-sample dataset).
- `android_app/app/src/main/java/com/health/openscale/core/bluetooth/scales/MiScaleS400Handler.kt` —
  wired `S400BodyFatV235.estimate(...)` into the `BODY_FAT` measurement field, falling back to
  the existing `S400BodyComposition` estimate only outside the verified input domain.

## Coefficients (for reference / re-derivation)

```
latent_FFM(h_cm, w_kg, age_years, R_high_ohm) = c0 + c1·h + c2·w + c3·age + c4·R_high
body_fat_pct = clamp(100 × (1 − latent_FFM / w_kg), 3.0, 75.0), rounded to 1 decimal

female: c = [-22.637482793351495, 0.33294993852603716, 0.2488996917777127,
             -0.01975349413536753, -0.007229906660150276]
male:   c = [-22.581459185537234, 0.33161141027191327, 0.37821882356760034,
             -0.018465167384231713, -0.008462253886384885]
```

Verified input domain (exact range of the 356 captured samples; outside it `estimate()` returns
`null` rather than extrapolating): height 145–200 cm, weight 40–120 kg, age 16–68, R_high
300–1000 Ω.

## Data provenance

The 356 request/response pairs used for fitting and validation were already captured in a prior
session via an authorized, quota-tracked calculation-only probe (Frida hooking
`SmartHomeRequestManager` inside the real Xiaomi Home app on a physical Android device, issuing
synthetic — not the user's real — height/weight/age/impedance combinations to
`/eco/scale/bcalc` and recording the server's response). No new server queries were issued for
this discovery; everything here was derived from data already on disk
(`s400_parity_v2/batch*_responses.json`, `s400_parity_v2/training_responses.json`). See
`s400_parity_v2/quota.json` for the query ledger if additional verification (bone, muscle, water,
visceral fat) requires new queries — that quota was explicitly re-approved by the user but not
yet spent at the time of this document.
