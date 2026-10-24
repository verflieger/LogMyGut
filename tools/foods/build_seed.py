"""Builds app/src/main/assets/foods.de.json from foods.src.txt and bls_subset.tsv (SPEC 4.4, 4.5).

Standard library only. Usage: python build_seed.py [--report FILE]

Source format (foods.src.txt), one food per line, fields separated by " | ":
    Name | size | g=150 | bls=F110100 | aliases=A, B | tags=gluten, fructans:L | no=fat | key=Old name | note=...
    Name | count:Stück | g=60 | ...
- Indentation (2 spaces per level) makes a line the child of the closest less-indented line above it.
- size: g is the normal (M) portion; S is half of it, L double. count:<label>: g is one piece.
- bls: BLS 4.0 code; lactose, fructose, polyols, fat and alcohol are then computed from its values.
- tags: hand-curated tags (also overriding computed ones); a suffix sets a threshold (M/L, or a count).
- no: tags this food does not have, though computed or inherited.
- key: the permanent seed key when the name changed (SPEC 4: seed keys never change).
Lines starting with "#" are comments; the line "== Gerichte" starts the dishes:
    Name | size | aliases=... | = Ingredient, Ingredient
"""
import argparse
import csv
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE.parents[1] / "app" / "src" / "main" / "assets" / "foods.de.json"
SEED_VERSION = 4
TAG_ORDER = ["lactose", "fructose", "polyols", "fructans", "gos", "gluten", "histamine",
             "fat", "caffeine", "alcohol", "spicy", "carbonated"]
COMPUTED = ["lactose", "fructose", "polyols", "fat", "alcohol"]

# Grams per eaten amount from which a computed tag applies (SPEC 4.5).
CUTOFF_G = {"lactose": 1.0, "fructose": 0.5, "polyols": 0.3, "fat": 15.0, "alcohol": 1.0}
# Count thresholds above 2 pieces aren't computed: like size L, 2 pieces is the largest normal amount (SPEC 4.2).
MAX_COUNT_THRESHOLD = 2


def per_100g(row: dict) -> dict[str, float | None]:
    def num(key):
        value = row[key].strip()
        if value in ("", "-"):
            return None  # not determined
        if value.startswith("<") or value == "TR":
            return 0.0  # below detection/quantification limit, or trace
        return float(value)

    fructose, glucose = num("FRUS"), num("GLUS")
    polyols = [num(k) for k in ("SORTL", "MANTL", "XYLTL")]
    return {
        "lactose": num("LACS"),
        "fructose": None if fructose is None else max(0.0, fructose - (glucose or 0.0)),  # excess fructose
        "polyols": None if all(p is None for p in polyols) else sum(p or 0.0 for p in polyols),
        "fat": num("FAT"),
        "alcohol": num("ALC"),
    }


def computed_tags(values: dict, unit: str, grams: float) -> dict[str, str | int | None]:
    """Tag -> threshold (None = always, "M"/"L", or a minimum count)."""
    tags = {}
    for tag, cutoff in CUTOFF_G.items():
        per_g = (values[tag] or 0.0) / 100
        if per_g <= 0:
            continue
        if unit == "size":
            for level, factor in (("S", 0.5), ("M", 1.0), ("L", 2.0)):
                if per_g * grams * factor >= cutoff:
                    tags[tag] = None if level == "S" else level
                    break
        else:
            for n in range(1, MAX_COUNT_THRESHOLD + 1):
                if per_g * grams * n >= cutoff:
                    tags[tag] = None if n == 1 else n
                    break
    return tags


def parse_tags(text: str) -> dict[str, str | int | None]:
    tags = {}
    for item in filter(None, (t.strip() for t in text.split(","))):
        name, _, threshold = item.partition(":")
        if name not in TAG_ORDER:
            sys.exit(f"unknown tag '{name}'")
        tags[name] = None if not threshold else int(threshold) if threshold.isdigit() else threshold
    return tags


def parse_source(path: Path):
    ingredients, dishes, stack = [], [], []
    in_dishes = False
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        if raw.strip() == "== Gerichte":
            in_dishes = True
            continue
        indent = len(raw) - len(raw.lstrip(" "))
        if indent % 2:
            sys.exit(f"line {number}: odd indentation")
        level = indent // 2
        if in_dishes:
            head, _, recipe = raw.strip().partition(" | = ")
            fields = head.split(" | ")
            dish = {"name": fields[0], "unit": fields[1], "ingredients": [i.strip() for i in recipe.split(",")]}
            dish.update(kv.split("=", 1) for kv in fields[2:])
            dishes.append(dish)
            continue
        fields = [f.strip() for f in raw.strip().split(" | ")]
        food = {"name": fields[0], "unit": fields[1], "level": level, "line": number}
        for kv in fields[2:]:
            key, sep, value = kv.partition("=")
            if not sep:
                sys.exit(f"line {number}: field without '=': {kv}")
            food[key] = value
        if "g" not in food:
            sys.exit(f"line {number}: '{food['name']}' needs g=")
        if level > len(stack):
            sys.exit(f"line {number}: indented too far")
        del stack[level:]
        food["parent"] = stack[-1]["name"] if stack else None
        stack.append(food)
        ingredients.append(food)
    return ingredients, dishes


def load_bls() -> dict[str, dict]:
    with open(HERE / "bls_subset.tsv", encoding="utf-8") as f:
        return {row["code"]: row for row in csv.DictReader(f, delimiter="\t")}


def resolve(ingredients, bls):
    """Full tag set per food (inheritance applied), plus cross-check warnings."""
    by_name = {f["name"]: f for f in ingredients}
    full, warnings = {}, []
    for food in ingredients:  # parents always come before their children
        unit = "size" if food["unit"] == "size" else "count"
        tags = dict(full[food["parent"]]) if food["parent"] else {}
        manual = parse_tags(food.get("tags", ""))
        if "bls" in food:
            row = bls.get(food["bls"]) or sys.exit(f"{food['name']}: BLS code {food['bls']} missing in bls_subset.tsv")
            values = per_100g(row)
            computed = computed_tags(values, unit, float(food["g"]))
            food["values"] = values
            # The food's own measured values replace inherited nutrient tags.
            for tag in COMPUTED:
                tags.pop(tag, None)
            tags.update(computed)
            for tag in COMPUTED:
                if tag in manual and manual[tag] != computed.get(tag, "-"):
                    shown = "none" if tag not in computed else fmt(tag, computed[tag])
                    warnings.append(f"{food['name']}: curated {fmt(tag, manual[tag])}, BLS gives {shown}")
        for tag in parse_tags(food.get("no", "")):
            tags.pop(tag, None)
        tags.update(manual)
        full[food["name"]] = tags
    return full, warnings, by_name


def fmt(tag, threshold):
    return tag if threshold is None else f"{tag}:{threshold}"


def build(ingredients, dishes, full, by_name):
    key_of = {f["name"]: f.get("key", f["name"]) for f in ingredients}
    out_ingredients = []
    for food in ingredients:
        tags = full[food["name"]]
        parent_tags = full[food["parent"]] if food["parent"] else {}
        own = {t: th for t, th in tags.items() if parent_tags.get(t, "-") != th}
        removed = [t for t in parent_tags if t not in tags]
        entry = {"name": food["name"]}
        if "key" in food:
            entry["key"] = food["key"]
        if food["parent"]:
            entry["parent"] = key_of[food["parent"]]
        if "aliases" in food:
            entry["aliases"] = [a.strip() for a in food["aliases"].split(",")]
        if food["unit"] == "size":
            entry["unit"] = "size"
        else:
            entry["unit"], entry["unitLabel"] = "count", food["unit"].split(":", 1)[1]
        ordered = sorted(own.items(), key=lambda kv: TAG_ORDER.index(kv[0]))
        entry["tags"] = [t for t, th in ordered if th is None]
        dose = {t: th for t, th in ordered if th is not None}
        if dose:
            entry["doseTags"] = dose
        if removed:
            entry["removeTags"] = sorted(removed, key=TAG_ORDER.index)
        entry["grams"] = float(food["g"])
        if "note" in food:
            entry["note"] = food["note"]
        if "bls" in food:
            entry["source"] = f"BLS 4.0 {food['bls']}"
        out_ingredients.append(entry)

    out_dishes = []
    for dish in dishes:
        missing = [i for i in dish["ingredients"] if i not in key_of]
        if missing:
            sys.exit(f"dish {dish['name']}: unknown ingredients {missing}")
        entry = {"name": dish["name"]}
        if "key" in dish:
            entry["key"] = dish["key"]
        if "aliases" in dish:
            entry["aliases"] = [a.strip() for a in dish["aliases"].split(",")]
        if dish["unit"] == "size":
            entry["unit"] = "size"
        else:
            entry["unit"], entry["unitLabel"] = "count", dish["unit"].split(":", 1)[1]
        entry["ingredients"] = [key_of[i] for i in dish["ingredients"]]
        out_dishes.append(entry)
    return {"version": SEED_VERSION, "tags": TAG_ORDER, "ingredients": out_ingredients, "dishes": out_dishes}


def changes_against(previous: dict, ingredients, full) -> list[str]:
    """Tag changes of foods that already existed in the previous seed (matched by key)."""
    def old_tags(entry):
        tags = {t: None for t in entry.get("tags", [])}
        tags.update(entry.get("doseTags", {}))
        return tags

    # Previous seeds before the hierarchy had complete tag lists per food.
    old = {e.get("key", e["name"]): old_tags(e) for e in previous.get("ingredients", []) if "parent" not in e}
    lines = []
    for food in ingredients:
        key = food.get("key", food["name"])
        if key in old and old[key] != full[food["name"]]:
            before = ", ".join(fmt(t, th) for t, th in old[key].items()) or "–"
            after = ", ".join(fmt(t, th) for t, th in full[food["name"]].items()) or "–"
            lines.append(f"{food['name']}: {before}  →  {after}")
    return lines


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=Path, help="write warnings and changes to this file")
    args = parser.parse_args()

    ingredients, dishes = parse_source(HERE / "foods.src.txt")
    full, warnings, by_name = resolve(ingredients, load_bls())
    seed = build(ingredients, dishes, full, by_name)
    previous = json.loads(OUT.read_text(encoding="utf-8")) if OUT.exists() else {}
    changes = changes_against(previous, ingredients, full)

    OUT.write_text(json.dumps(seed, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    with_bls = sum("bls" in f for f in ingredients)
    print(f"{len(ingredients)} ingredients ({with_bls} with BLS values), {len(dishes)} dishes -> {OUT.name}")
    print(f"{len(warnings)} curated tags differ from BLS; {len(changes)} existing foods changed tags")
    if args.report:
        args.report.write_text(
            "# Curated vs. BLS\n" + "\n".join(warnings) + "\n\n# Tag changes of existing foods\n" + "\n".join(changes) + "\n",
            encoding="utf-8",
        )


if __name__ == "__main__":
    main()
