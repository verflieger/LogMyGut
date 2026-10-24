"""Extracts the BLS rows referenced by foods.src.txt into bls_subset.tsv.

Needs openpyxl and the BLS 4.0 data file, which is not in the repository:
    data-sources/BLS_4_0_Daten_2025_DE.xlsx  (Max Rubner-Institut 2025, Bundeslebensmittelschlüssel 4.0,
    DOI 10.25826/Data20251217-134202-0; free to use, see https://www.blsdb.de/faq)

Usage: python extract_bls.py   (from any directory)
"""
import csv
import re
from pathlib import Path

import openpyxl

HERE = Path(__file__).resolve().parent
XLSX = HERE.parents[1] / "data-sources" / "BLS_4_0_Daten_2025_DE.xlsx"
NUTRIENTS = ["FAT", "ALC", "GLUS", "FRUS", "LACS", "SORTL", "MANTL", "XYLTL", "FIBLMW"]


def referenced_codes() -> set[str]:
    text = (HERE / "foods.src.txt").read_text(encoding="utf-8")
    return set(re.findall(r"\bbls=([A-Z0-9]{7})\b", text))


def main() -> None:
    codes = referenced_codes()
    sheet = openpyxl.load_workbook(XLSX, read_only=True).worksheets[0]
    rows = sheet.iter_rows(values_only=True)
    header = [str(h) for h in next(rows)]
    columns = {n: next(i for i, h in enumerate(header) if h.startswith(n + " ") and "[g/100g]" in h) for n in NUTRIENTS}

    found = {}
    for row in rows:
        if row[0] in codes:
            found[row[0]] = [row[0], row[1]] + ["" if row[columns[n]] is None else str(row[columns[n]]) for n in NUTRIENTS]

    with open(HERE / "bls_subset.tsv", "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f, delimiter="\t", lineterminator="\n")
        writer.writerow(["code", "name"] + NUTRIENTS)
        writer.writerows(found[c] for c in sorted(found))
    missing = codes - found.keys()
    print(f"{len(found)} BLS rows written" + (f"; unknown codes: {sorted(missing)}" if missing else ""))


if __name__ == "__main__":
    main()
