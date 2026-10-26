# Food tags: architecture

How trigger tags are stored, resolved and kept in sync with the seed. Behaviour and wording: SPEC 4, 4.3–4.5, 6.3.

## Tags

- `domain/Model.kt` `enum class Tag(key, defaultWindow, offsetBy)`. The key is persisted; `offsetBy` names the
  medication that takes a meal out of the comparison (lactose ← `MedEffect.ENZYME`).
- `ui/common/TagTexts.kt`: label and info texts per tag (`strings.xml`, `strings_info.xml`, EN + DE).
- `tools/foods/build_seed.py` lists the same keys in `TAG_ORDER`; a test checks `foods.json` against `Tag.entries`.

## Rows: seed vs. user

`FoodTagEntity(foodId, tag, threshold, removed, source)`, domain type `TagRow` (`domain/EffectiveTags.kt`).

- `SEED` rows are the seed's own tags and removals, relative to the seed parent. Only `SeedImporter` writes them.
- `USER` rows are the user's decisions. Per tag, a `USER` row wins (`ownRows`).
- Resolution: `ownRows`, then live inheritance from the parent (`resolveTags`, max. 3 levels).
- Saving an attached food stores only the differences from what the seed gives (`userRowsFor`); detached and
  user-created foods store their full own list (`fullRowsFor`).

## Attached and detached built-in ingredients

`FoodEntity.tagsDetached`; `tagsFollowSeed()` = built-in ingredient, not detached.

- Attached: the seed sync rewrites `SEED` rows and moves the food to its seed parent, even if the user edited it.
  Invariant: an attached food's parent is its seed parent.
- Detached: tags and parent are never touched by the seed; only `USER` rows. Causes: the user changed the parent,
  the migration to DB v6 (food edited before), a seed placement that isn't possible, or the food left the seed.
  Detaching keeps the effective tags unchanged.
- `SeedImporter.reset` → `ResetResult` re-attaches ("Auf Standard zurücksetzen"); it checks first (`NOT_IN_SEED`, `PARENT_IMPOSSIBLE`)
  and changes nothing if it can't.

## Hierarchy checks

`domain/FoodHierarchy.kt`: `parentProblems` (cycles, depth > 3 incl. descendants) is the one check. The editor uses
it via `FoodRepository.canBeParent`; the seed sync via `planSeedParents`, which validates the final parent map and
decides which attached foods to detach instead of moving.

## Seed data

`build_seed.py` computes nutrient tags from BLS values with `COMPUTED_RULES` (`Nutrient`, `ExcessFructose`,
`NutrientSum`); other tags are curated in `foods.src.txt` (`tags=`, `no=`). A child's own known value replaces the
inherited result; an unknown one keeps it.

## Adding a built-in tag

1. `Tag` entry (key, sourced window, optional `offsetBy`); `TagTexts` entry; strings EN + DE, checked against the
   SPEC wording rules.
2. `build_seed.py`: `TAG_ORDER`, and a `COMPUTED_RULES` entry if BLS can derive it (else curate `tags=`); rebuild
   `foods.json` and review the report.
3. Keyword rules DE/EN; SPEC 4.3, 4.5, 8 (and 6.3 for an `offsetBy`); fastlane changelog.

Attached foods get the tag on the next start. Detached foods, and children inheriting only from a detached parent,
get it after a reset.

## Inspecting it in the app

Settings → "Expertenmodus" shows "Abgekoppelte Zutaten" and "Änderungen gegenüber Standard". The comparison with
the seed is `seedDifferences` in `data/seed/SeedDiff.kt`, exposed as `FoodRepository.observeSeedChanges`.
