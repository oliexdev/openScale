"""Build the foot-to-foot fixtures for S400FootToFootTest.

Both sources are licensed CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/).
Changes: columns selected and renamed, the wide per-visit layout reshaped to one
row per measurement, rows with a missing value dropped. No values altered.

tanita_uww.csv
  Aerenhouts D, Clarys P, Taeymans J, Van Cauwenberg J, et al. Estimating body
  composition in adolescent sprint athletes: comparison of different methods in
  a 3 years longitudinal design. PLOS ONE 2015;10:e0136788.
  doi:10.1371/journal.pone.0136788, S1 File (journal.pone.0136788.s001).
  Tanita TBF-410, foot to foot, 50 kHz; reference underwater weighing (Siri).
  Columns: id, visit, male (1/0), h (cm), w (kg), z (ohm), bf_tanita (device %),
  bf_uww (%). Age is not given per subject (mean about 15 y at the first of six
  visits over three years).

seca_segments.csv
  Perez et al. Multidimensional dataset for the analysis of body composition in
  the Colombian adult population. Mendeley Data, 2025. doi:10.17632/9sydrz8975.1.
  seca mBCA 525, eight electrodes, supine; adults 18-81.
  Columns: id, sample, male (1/0), and resistance in ohm of the left leg (ll),
  right leg (rl), left body side (lb) and right body side (rb) at 50 and 200 kHz.
  A body side is the hand-to-foot path; ll + rl approximates foot to foot
  without the pelvis.

Usage: python make_foot_to_foot_fixtures.py <journal.pone.0136788.s001> <seca .xlsx>
Requires pandas, xlrd and openpyxl.
"""
import sys
from pathlib import Path

import pandas as pd

HERE = Path(__file__).parent


def tanita(src: Path) -> None:
    sheet = pd.read_excel(src, sheet_name="cross sect")
    rows = []
    for v in range(1, 7):
        d = sheet[["Participant", "SEX", f"LL{v}", f"LG{v}", f"IMPED(Ω){v}", f"vet%tanita{v}", f"vet%SIRIUWW{v}"]].copy()
        d.columns = ["id", "male", "h", "w", "z", "bf_tanita", "bf_uww"]
        d.insert(1, "visit", v)
        rows.append(d)
    df = pd.concat(rows).apply(pd.to_numeric, errors="coerce").dropna()
    df = df.astype({"id": int, "visit": int, "male": int})
    df = df.round({"h": 1, "w": 1, "z": 0, "bf_tanita": 1, "bf_uww": 2})
    df.sort_values(["id", "visit"]).to_csv(HERE / "tanita_uww.csv", index=False, lineterminator="\n")
    print(f"{len(df)} rows -> tanita_uww.csv")


def seca(src: Path) -> None:
    e = pd.read_excel(src, sheet_name="Electrical ")
    user = pd.read_excel(src, sheet_name="User")[["id", "gender"]]
    e = e.merge(user, on="id")
    out = pd.DataFrame({"id": e.id, "sample": e.id_Sample, "male": (e.gender == "Male").astype(int)})
    for f in (50, 200):
        for seg in ("LL", "RL", "LB", "RB"):
            out[f"{seg.lower()}{f}"] = e[f"bioimpedance_R_({f}_kHz){seg}_value"].round(1)
    out = out.dropna().astype({"id": int, "sample": int})
    out.to_csv(HERE / "seca_segments.csv", index=False, lineterminator="\n")
    print(f"{len(out)} rows -> seca_segments.csv")


if __name__ == "__main__":
    tanita(Path(sys.argv[1]))
    seca(Path(sys.argv[2]))
