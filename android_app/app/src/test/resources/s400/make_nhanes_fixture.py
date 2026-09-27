"""Build nhanes_1999_2004.csv.gz for S400NhanesValidationTest.

Source: CDC NHANES 1999-2000, 2001-02 and 2003-04 public-use files (US
government work, public domain), downloaded from
https://wwwn.cdc.gov/Nchs/Data/Nhanes/Public/<year>/DataFiles/<file>.xpt:
BIX (Xitron Hydra 4200 bioimpedance spectroscopy, right hand to right foot),
DXX (Hologic QDR-4500A whole-body DXA), DEMO and BMX, with suffixes "", "_B"
and "_C".

Rows: adults 18-49 with a complete BIA exam (BIAEXSTS 1), a complete DXA exam
(DXAEXSTS 1), a non-imputed DXA total (DXITOT 0), first imputation set
(_MULT_ 1), and |Z_50| > |Z_250|.

Columns:
  cycle     A, B or C (1999-2000, 2001-02, 2003-04)
  male      1 or 0 (RIAGENDR)
  age       years (RIDAGEYR)
  h, w      height cm (BMXHT), weight kg (BMXWT)
  z50       |Z| at 50 kHz, ohm: hypot(BIXS050K, BIXC050K)
  z250      |Z| at 250 kHz, ohm: linear in log f between the 245 and 273 kHz
            magnitudes
  bf_dxa    DXA whole-body fat, % (DXDTOPF)
  re        Xitron Cole fit R_E, ohm (BIDRECF)
  ecf, tbw  Xitron full-spectrum ECF and TBW, L (BIDECF, BIDTBW); empty when
            not reported

Usage: python make_nhanes_fixture.py <dir with the 12 .xpt files>
Requires pandas.
"""
import gzip
import io
import math
import sys
from pathlib import Path

import numpy as np
import pandas as pd


def main(src: Path, out: Path) -> None:
    parts = []
    for cycle, suffix in (("A", ""), ("B", "_B"), ("C", "_C")):
        bix = pd.read_sas(src / f"BIX{suffix}.xpt")
        dxx = pd.read_sas(src / f"DXX{suffix}.xpt")
        demo = pd.read_sas(src / f"DEMO{suffix}.xpt")[["SEQN", "RIAGENDR", "RIDAGEYR"]]
        bmx = pd.read_sas(src / f"BMX{suffix}.xpt")[["SEQN", "BMXWT", "BMXHT"]]
        dxx = dxx[(dxx._MULT_ == 1) & (dxx.DXAEXSTS == 1) & (dxx.DXITOT < 0.5)]
        bix = bix[bix.BIAEXSTS == 1]
        m = bix.merge(dxx, on="SEQN").merge(demo, on="SEQN").merge(bmx, on="SEQN")
        m = m[(m.RIDAGEYR >= 18) & (m.RIDAGEYR <= 49)]
        m = m.dropna(subset=["BIXS050K", "BIXC050K", "BIXS245K", "BIXC245K", "BIXS273K",
                             "BIXC273K", "BMXWT", "BMXHT", "DXDTOPF", "BIDRECF"])
        z245 = np.hypot(m.BIXS245K, m.BIXC245K)
        z273 = np.hypot(m.BIXS273K, m.BIXC273K)
        t = (math.log(250) - math.log(245)) / (math.log(273) - math.log(245))
        part = pd.DataFrame({
            "cycle": cycle,
            "male": (m.RIAGENDR == 1).astype(int),
            "age": m.RIDAGEYR.astype(int),
            "h": m.BMXHT.round(1),
            "w": m.BMXWT.round(1),
            "z50": np.hypot(m.BIXS050K, m.BIXC050K).round(2),
            "z250": (z245 + t * (z273 - z245)).round(2),
            "bf_dxa": m.DXDTOPF.round(1),
            "re": m.BIDRECF.round(1),
            "ecf": m.BIDECF.round(2),
            "tbw": m.BIDTBW.round(2),
        })
        parts.append(part[part.z50 > part.z250])
    df = pd.concat(parts, ignore_index=True)
    buf = io.StringIO()
    df.to_csv(buf, index=False, lineterminator="\n")
    with open(out, "wb") as f, gzip.GzipFile(fileobj=f, mode="wb", mtime=0, filename="") as gz:
        gz.write(buf.getvalue().encode("ascii"))
    print(f"{len(df)} rows -> {out}")


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(__file__).with_name("nhanes_1999_2004.csv.gz"))
