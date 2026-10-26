# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

LogMyGut is a private, fully offline Android app (Kotlin, Jetpack Compose, Room, DataStore) for logging food,
drinks, bowel movements and symptoms. **`SPEC.md` is the source of truth** for behaviour, data model, backup format
and wording; code comments cite it as "SPEC 4.2" etc. Update SPEC.md alongside behaviour changes.

Code, identifiers and SPEC are English. The UI is **English** (default, `res/values/`) and **Austrian German**
(`res/values-de/`: Semmel, Topfen, "Jänner"); every string needs both. Dates go through `localizedFormat` in
`ui/common/Formatting.kt`, never fixed patterns.

## Commands

The system Java here is JRE-only; run Gradle with Android Studio's JBR:

```sh
export JAVA_HOME=~/.local/opt/android-studio/jbr
./gradlew assembleDebug                      # debug APK
./gradlew installDebug                       # install on attached phone / emulator
./gradlew testDebugUnitTest                  # all unit tests (JVM + Robolectric)
./gradlew testDebugUnitTest --tests 'at.mtr.logmygut.domain.TriggerAnalysisTest'          # one class
./gradlew testDebugUnitTest --tests 'at.mtr.logmygut.domain.TriggerAnalysisTest.someName' # one test
./gradlew assembleRelease                    # signed release APK (see below)
```

Ask the user before running builds or tests. There are no instrumented (`androidTest`) tests; UI tests use Compose
test + Robolectric under `app/src/test`.

Seed and asset generators (Python, standard library unless noted):

```sh
python tools/foods/build_seed.py [--report FILE]   # foods.src.txt + foods.en.txt + bls_subset.tsv -> app/src/main/assets/foods.json
python tools/foods/extract_bls.py                  # needs openpyxl + data-sources/BLS_4_0_Daten_2025_DE.xlsx (not in git)
python tools/icons/build_bristol.py                # from repo root; writes res/drawable/bristol_<n>.xml
```

## Architecture

- **Manual DI**: `LogMyGutApp` creates one `AppContainer` (database, repositories, settings, app-wide scope);
  screens/ViewModels get dependencies from `(application as LogMyGutApp).container`. No Hilt.
  On start it syncs the seed foods (`SeedImporter`) and keeps the daily reminder (WorkManager) scheduled.
- **Layers**: `data/` (Room entities/DAOs in `data/db`, repositories, DataStore `SettingsRepository`),
  `domain/` (pure Kotlin, no Android deps: tag resolution, trigger analysis, trends, day score, tag suggestion,
  search normalisation), `ui/` (one package per screen area; navigation and bottom bar in `ui/LogMyGutRoot.kt`).
- **Food model** (SPEC 4): foods are ingredients (carry tags) or dishes (lists of ingredients, no own tags).
  Ingredients may have a parent (≤ 3 levels) and inherit its tags live; a child can override or remove
  (`FoodTag.removed`) an inherited tag. Tags can be dose-dependent (`minSize` / `minCount`). Effective tags are
  always resolved from **current** food settings (`domain/EffectiveTags.kt`), so fixing a tag corrects past analysis.
  A meal stores its **actual ingredient list** per dish item (`MealItemIngredient`), so editing a recipe never
  rewrites past meals.
- **Seed data**: `foods.json` is generated — edit `tools/foods/foods.src.txt` (German) and `foods.en.txt` (English,
  one line per food, keyed by German name) and rerun `build_seed.py`, never the JSON by hand. Seed keys are permanent
  (rename via `key=Old name`). The importer never changes an existing food's unit and updates name, aliases, note,
  portion weight, drink flag and recipe only on built-in foods without `userModified` (an edited food's name still
  follows the language unless renamed). Tag rows have a `source` (SEED/USER, USER wins per tag): attached
  built-in ingredients get new SEED rows and the seed parent even when edited (USER rows stay); detached ones
  (`tagsDetached`, after a user parent change, the v6 migration, an impossible seed placement or the seed no longer
  having it) are frozen until "reset to default" (SPEC 4).
- **Food names follow the language** (SPEC 4): `SeedImporter.import(seed, language)` stores built-in names in the
  app's language with the other language's names as aliases; it runs on start, on configuration change
  (`LogMyGutApp.onConfigurationChanged`) and after a backup restore. Changed built-ins follow the language unless the
  user renamed them.
- **Unit labels**: built-in labels are stored as German keys (`UnitLabelKeys` in `domain/Model.kt`), shown via
  `Resources.unitName`/`countLabel`; typed labels go through `unitLabelKey`. `DRINK_LABELS` in `build_seed.py` must
  match `DrinkUnitLabels`.
- **Database changes**: bump `version` in `LogMyGutDatabase`, add a `Migration` to `data/db/Migrations.kt`
  (`ALL_MIGRATIONS`), and commit the exported schema in `app/schemas/`. `MigrationTest` reads those schemas
  (they are added to debug assets for Robolectric).
- **Backup** (SPEC 7): its own DTOs in `data/backup/BackupFile.kt`, decoupled from Room entities, so DB changes
  don't silently change the format. A new backup version must still read all older versions; one-time exception:
  v3 (tag `source`, `tagsDetached`) doesn't read versions before 3. Import is all-or-nothing in one transaction.
- **Timestamps** are stored as UTC epoch millis plus `zoneId` so day assignment survives time-zone changes.

## Constraints to keep

- **No network.** The manifest removes `INTERNET`/`ACCESS_NETWORK_STATE`, `allowBackup="false"`; no analytics or
  crash reporting. `ManifestPrivacyTest` enforces this.
- **EU MDR wording** (SPEC "Intended purpose and wording"): analysis texts describe frequencies in the user's own
  entries ("deutlich häufiger" / "noticeably more often"), never causes, suspicions or diagnoses ("Verdacht", "Unverträglichkeit"), never
  point to a test or diet, never say whether Rome IV criteria are met, and use no alarm styling on results.
- **Releases**: raise `versionCode`/`versionName` in `app/build.gradle.kts` for every APK handed out. Release signing
  reads `~/.config/logmygut/keystore.properties` (not in git; without it the APK stays unsigned). APKs are copied to
  `dist/` (git-ignored). The developer's phone runs the debug build. Store texts, screenshots and one changelog per
  `versionCode` live in `fastlane/metadata/android/` (en-US, de-DE; F-Droid reads them); screenshots use made-up
  sample data only.

## Device / emulator

Emulator `LogMyGut_API36`: `~/Android/Sdk/emulator/emulator -avd LogMyGut_API36 -no-window`. Sample data can be
loaded with the app stopped:
`adb shell "run-as at.mtr.logmygut sqlite3 /data/data/at.mtr.logmygut/databases/logmygut.db" < file.sql`.
