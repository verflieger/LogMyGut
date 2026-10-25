# LogMyGut — Specification v1

A private, fully offline Android app for logging food, bowel movements and digestive
symptoms. It surfaces *hints* about which food triggers correlate with symptoms.
Single user (the author), sideloaded. Repo, code and identifiers are in English; the UI is in Austrian German.

> LogMyGut is a self-observation tool, not a diagnostic device. All analysis
> results are presented as hints to discuss with a doctor.

## 1. Platform & constraints

| Item | Decision |
|---|---|
| Language / UI | Kotlin, Jetpack Compose (Material 3) |
| Persistence | Room (SQLite) for data, Jetpack DataStore for settings |
| Min / target SDK | 34 (Android 14) / latest stable |
| Package id | `at.mtr.logmygut` |
| App name | LogMyGut |
| Distribution | Sideload: debug builds via Android Studio / adb, release APKs handed out directly. No Play Store. |
| Network | **None.** No `INTERNET` permission in the manifest. No analytics, crash reporting or cloud backup (`android:allowBackup="false"`). |
| App lock | None (relies on the device lock screen) |
| Localization | All UI text in `res/values/strings.xml`, in **Austrian German** (Semmel, Topfen, Erdäpfel …). Standard German food names are search aliases. Dates and times are formatted with locale `de-AT` (e.g. "Jänner"). English can be added later. |

Permissions: `POST_NOTIFICATIONS` (reminder), `SCHEDULE_EXACT_ALARM` is **not** used.
The daily reminder uses an inexact alarm or WorkManager, which is acceptable for this purpose.

**Release builds** (APKs given to others):
- **Signing:** `assembleRelease` signs with the key in `~/.config/logmygut/` (`release.jks` plus
  `keystore.properties` with its password). Neither is in git. Back the folder up: every update must be
  signed with this same key, otherwise Android refuses it and the user would have to uninstall, losing
  their data.
- **Without that folder,** the release APK stays unsigned and can't be installed.
- **Versions:** raise `versionCode` (and `versionName`) in `app/build.gradle.kts` for every APK handed out.
- **The developer's own phone** runs the debug build (debug key), so it isn't switched to the release key.

## 2. Navigation

Bottom navigation with four tabs:

1. **Heute**: quick-add buttons (Essen, Stuhlgang, Beschwerden, Medikament) above today's timeline.
   Shows a "Tages-Check-in" card if today's check-in is missing.
2. **Verlauf**: month calendar (days coloured by a "bad day" score, see 6.2). Tapping a day opens its day view:
   that day's check-in (editable) and timeline. Days with only meals or medication have no score and show a dot.
   Weeks start on Monday; future days and months can't be opened. Day boundaries use the phone's current time zone.
3. **Analyse**: trends and trigger analysis (section 6).
4. **Einstellungen**: reminder, analysis parameters, **Lebensmittel & Gerichte** (section 4.1), backup, info pages.

Every timeline entry can be tapped to edit or deleted (with an undo snackbar).

## 3. Logging

### 3.1 Common to all entries
- Timestamp defaults to *now* and can be edited to any past date/time (date and time pickers).
  Future timestamps are not allowed.
- Optional free-text note.

### 3.2 Meal ("Essen")
- One or more **items**. Each item is either an **ingredient** or a **dish** (see section 4).
  **Rule: you log what you ate (usually the dish); the app analyzes the ingredients.**
- Search uses autocomplete over the names and aliases of both ingredients and dishes (case- and
  umlaut-insensitive, prefix and substring). Dishes and ingredients get different icons.
  Ranking: exact name/alias match, then name prefix, then alias prefix, then substring; within each, recently used first.
- An unknown name can be created inline:
  - as an **ingredient**: tags are suggested from `app/src/main/assets/keyword_rules.de.json` and shown pre-selected;
    the user confirms or edits them;
  - as a **dish**: the user picks its ingredients from the same search; missing ingredients can be
    created inline.
- **Amount per item** depends on the item's `unit`:
  - `count`: integer stepper, default 1, label from `unitLabel` (e.g. "2 Scheiben").
  - `size`: S / M / L chips, default M.
- **Zutaten anpassen** on a dish item: remove or add ingredients for this meal only.
  The saved dish stays unchanged. A checkbox **"Auch im Gericht dauerhaft speichern"** (off by default)
  also writes the change into the saved dish, so it applies from now on. Past meals are not touched.
- Meal flag: **auswärts gegessen** (restaurant/canteen; boolean).
- Display label derived from time, not stored: 05:00–10:29 Frühstück · 10:30–14:29 Mittagessen ·
  14:30–16:59 Jause · 17:00–21:29 Abendessen · otherwise Jause.

### 3.3 Bowel movement ("Stuhlgang")
- **Bristol type 1–7** (required), chosen from a visual picker with short German descriptions.
  The illustrations are drawn for this app (not copied from the copyrighted original chart).
- **Dringlich** (urgency): yes/no.
- **Bauchschmerzen/Krämpfe vorher** (pain before): switch. Only when on: **Danach** besser / gleich / schlimmer (required).
  Both relief and worsening count as "pain related to defecation" (Rome IV).

### 3.4 Symptoms ("Beschwerden")
One screen with a 0–10 slider per symptom. A slider the user doesn't touch is not saved.
Each touched slider becomes its own `SymptomEntry` with the shared timestamp.

| Key | UI label |
|---|---|
| `BLOATING` | Blähbauch / Völlegefühl |
| `PAIN` | Bauchschmerzen / Krämpfe |
| `GAS` | Blähungen (Winde) |
| `NAUSEA` | Übelkeit |
| `FATIGUE` | Müdigkeit |
| `HEADACHE` | Kopfschmerzen |

### 3.5 Medication ("Medikament")
- Name (free text, autocomplete from previous entries).
- Optional effect tag: `ANTIDIARRHEAL` (stopfend, e.g. Loperamid/Imodium), `ENZYME` (Enzym, e.g. Laktase),
  `ANTIBIOTIC`, `PAINKILLER`, `OTHER`. The tag is remembered per name and pre-filled next time.

### 3.6 Daily check-in
- Once per calendar day: **Stress** 1–5 and **Schlafqualität** 1–5.
- Prompted by the daily reminder and the card on "Heute". Editable afterwards from the day view.

### 3.7 Reminder
- One daily notification at a user-chosen time (default 21:00, can be switched off):
  "Hast du heute alles eingetragen?" Tapping it opens Heute. Survives reboot.
- Scheduled with WorkManager, one run at a time, each scheduling the next. This keeps 21:00 across
  daylight-saving changes. The time may be off by a few minutes; exact alarms would need an extra permission.
- Android 13+ needs the notification permission: the app asks once on first start, and Einstellungen
  shows a hint with "Erlauben" while it's missing.

## 4. Food library

Two kinds of entries:

- **Ingredient (Zutat)**: anything eaten or bought as one thing, including products whose
  recipe you don't control (Müsli, Kekse, Tomatensauce, Brühe). Ingredients carry tags.
- **Dish (Gericht)**: a named list of ingredients with no tags of its own. Its triggers are the union of its
  ingredients' (effective) tags. Dishes cannot contain dishes.

Seed data and editing:

- Seeded from `app/src/main/assets/foods.de.json`, which is **generated** from `tools/foods/foods.src.txt` by
  `tools/foods/build_seed.py` (see 4.5). About 380 ingredients and 60 dishes, with Austrian names and standard German
  aliases, `unit`, `unitLabel`, and for ingredients `grams`, `tags`, optional `doseTags`, `parent`, `removeTags` and `note`.
  Dishes list their `ingredients` by seed key.
- On every app start the importer syncs the built-in foods with the seed (cheap when nothing changed):
  missing entries are added; built-in entries the user hasn't changed get the seed's current name, aliases, note,
  tags, parent or recipe. The unit of an existing food never changes, because logged meal items depend on it.
- Users can edit any ingredient (tags, dose thresholds, unit, aliases, parent) or dish (ingredient list), create new ones,
  and hide built-in ones. Editing sets `userModified`; such entries (and user-created ones) are never touched by the seed.
- **Seed keys are permanent.** A seed entry's `key` (default: its `name`) identifies it across versions. To rename an
  entry, change `name` and set `key` to the old name.

How meals and tags relate over time:

- A meal stores its **actual ingredient list** (after any per-meal adjustment), so editing a dish later doesn't
  rewrite past meals.
- Tags are always resolved from an ingredient's **current** settings, so fixing a wrong tag corrects past
  analysis too.

### 4.1 Library screen ("Lebensmittel & Gerichte")
Reached from Einstellungen. Every change applies **from now on**: past meals keep the ingredients they
had, and tag changes affect the analysis as described above.

```
┌─────────────────────────────┐
│ ← Lebensmittel & Gerichte    │
│ [ Gerichte ] [ Zutaten ]     │  ← two tabs
│ 🔍 Suchen…                   │
│ Eigene                       │
│   🍲 Omas Gulasch        ›   │
│ Vorgegeben                   │
│   🍕 Pizza               ›   │
│   🍝 Spaghetti Bolognese ›   │
│                      ┌───┐   │
│                      │ + │   │  ← new dish / ingredient
│                      └───┘   │
└─────────────────────────────┘
```

**Dish detail / edit**

```
┌─────────────────────────────┐
│ ← Spaghetti Bolognese    ⋮  │  ⋮ = Ausblenden, Duplizieren,
│ Name  [Spaghetti Bolognese] │      Auf Standard zurücksetzen
│ Menge  (•) S/M/L  ( ) Stück │
│ Zutaten                      │
│   Nudeln                 ✕  │
│   Hackfleisch            ✕  │
│   Tomatensauce           ✕  │
│   Zwiebel                ✕  │
│   + Zutat hinzufügen         │
│ Auslöser (automatisch)       │
│   Fruktane · Gluten ·        │
│   Histamin                   │
│            [ Speichern ]     │
└─────────────────────────────┘
```

- **Ingredient detail:** name, aliases, unit (S/M/L or count with label), tags as toggle chips, an optional
  threshold per tag ("immer" / "ab M" / "ab L", or "ab N Stück"), note, and a "In N Gerichten verwendet" list.
- **Duplizieren** creates a variant ("Bolognese (vegetarisch)").
- **Ausblenden** hides an entry from search; it is never deleted, because past meals reference it.
  Built-in entries offer **"Auf Standard zurücksetzen"**, which restores the seed version.
- **Edit marker:** a built-in entry the user has edited is marked "geändert".
- **Shortcut:** long-press on a search result in the meal entry opens the same detail screen.

### 4.2 Dose-dependent tags
Many FODMAP triggers matter only above a certain amount (e.g. 1 EL Ketchup is fine). An ingredient can have
`doseTags`: a tag that only applies from a minimum amount.

- **Size-based ingredients:** the threshold is `"M"` or `"L"`.
- **Count-based ingredients:** the threshold is a number, e.g. Hafermilch → fructans from 2 Gläser.
- **The UI** shows a dose tag as "Fruktane (ab L)".

Effective amount of an ingredient inside a dish:

| Dish amount | Size-based ingredient | Count-based ingredient |
|---|---|---|
| S / M / L | same size as the dish | S, M → 1 · L → 2 |
| 1 piece | M | 1 |
| 2+ pieces | L | 2 |

A per-meal **added** ingredient gets the dish's effective size.

### 4.3 Trigger tags

| Key | UI label | Default analysis window after a meal |
|---|---|---|
| `lactose` | Laktose | 30 min – 8 h |
| `fructose` | Fruktose | 30 min – 8 h |
| `polyols` | Polyole (Sorbit, Mannit …) | 30 min – 8 h |
| `fructans` | Fruktane | 2 h – 24 h |
| `gos` | GOS (Hülsenfrüchte) | 2 h – 24 h |
| `gluten` | Gluten | 2 h – 48 h |
| `histamine` | Histamin | 15 min – 4 h |
| `fat` | Fettreich | 1 h – 6 h |
| `caffeine` | Koffein | 0 – 2 h |
| `alcohol` | Alkohol | 1 h – 24 h |
| `spicy` | Scharf | 1 h – 24 h |
| `carbonated` | Kohlensäure | 0 – 3 h |

Windows can be edited in settings. These defaults are approximate onset ranges from clinical literature,
and an info page explains each tag (what it is, typical foods, which test a doctor can run).

### 4.4 Food hierarchy

Ingredients can have a **parent**: a more general food, e.g. Essig → Apfelessig, Balsamico, Branntweinessig, or
Käse → Weichkäse → Camembert. At most 3 levels.

- The parent is a normal ingredient and can be logged itself ("Essig" when the kind is unknown).
- A child **inherits the parent's tags live**: changing a tag on Essig changes it for every vinegar that doesn't set
  that tag itself.
- A child's own tag entry replaces the inherited one (e.g. a different dose threshold). A child can also **remove**
  an inherited tag (Branntweinessig removes histamine).
- Search results show the parent ("Apfelessig · Essig") and, when the query matched an alias, that alias
  ("Paradeiser" with „Tomate“).
- The analysis stays tag-based. Later, per-food views may roll children up into their parent.

### 4.5 Seed data sources

- **Nutrient tags** (lactose, fructose, polyols, fat, alcohol) are computed from the **Bundeslebensmittelschlüssel
  (BLS) 4.0** (Max Rubner-Institut 2025, DOI 10.25826/Data20251217-134202-0), free to use including for apps.
  Only the needed rows are kept in `tools/foods/bls_subset.tsv`; the full file is not in the repository.
- Each ingredient has a portion weight: grams of an M portion (S = ½, L = 2×) or of one piece. A tag applies from
  the smallest amount whose content reaches the cutoff, which gives "always", "ab M", "ab L" or "ab 2 Stück":

  | Tag | Cutoff per eaten amount | Basis |
  |---|---|---|
  | lactose | ≥ 1 g lactose | Monash cutoff (Varney et al. 2017) |
  | fructose | ≥ 0.5 g fructose in excess of glucose | Monash uses 0.15 g; raised because composition tables aren't FODMAP measurements and small sugar differences are noise |
  | polyols | ≥ 0.3 g sorbitol + mannitol + xylitol | Monash: 0.2 g per polyol |
  | fat | ≥ 15 g fat | App heuristic (no accepted cutoff) |
  | alcohol | ≥ 1 g alcohol | any alcoholic drink |

  Count thresholds are computed up to 2 pieces only (2 pieces ≈ L, SPEC 4.2).
- **Curated tags** (fructans, GOS, gluten, histamine, caffeine, spicy, carbonated) come from published Monash FODMAP
  findings and histamine-intolerance lists. Where Monash measured a food differently than BLS suggests
  (e.g. Heidelbeeren, Brokkoli, Stangensellerie), the curated tag wins; the build prints these conflicts.
- The SIGHI histamine list is not used as data: it is copyrighted and members-only.
- The BLS must be credited on the info pages (section 8).
- The portion weight is stored per food (`portionGrams`) and shown next to amounts in the meal editor:
  "S · 15 g | M · 30 g | L · 60 g", or "2 Stück · ≈ 120 g". Dishes and foods without a weight show no grams.

## 5. Data model (Room)

```
Food(id, kind: INGREDIENT|DISH, name, aliases: List<String>, unit: COUNT|SIZE, unitLabel?, note?,
     builtIn: Boolean, userModified: Boolean, hidden: Boolean, seedKey?,
     searchText, lastUsedAt: Instant?, parentId?,       -- searchText = normalized "name|alias|…"; lastUsedAt for ranking
     portionGrams: Double?)                             -- grams of an M portion / one piece; null if unknown
                                                        -- seedKey = the seed entry's key (never changes)
                                                        -- parentId: ingredients only, SPEC 4.4
FoodTag(foodId, tag, minSize: M|L?, minCount: Int?,     -- PK (foodId, tag); both null = always applies
        removed: Boolean)                               -- removed = cancels the tag inherited from the parent
DishIngredient(dishId, ingredientId)                    -- the saved recipe
Meal(id, timestamp: Instant, zoneId, eatenOut: Boolean, note?)
MealItem(id, mealId, foodId, count: Int?, size: S|M|L?) -- foodId = ingredient or dish
MealItemIngredient(mealItemId, ingredientId)            -- only for dish items: actual ingredients
                                                        -- of this meal (copied from recipe, then tweaked)
BowelMovement(id, timestamp, zoneId, bristol: 1..7, urgent: Boolean, painBefore: Boolean,
              painAfter: BETTER|SAME|WORSE?, note?)   -- painAfter set exactly when painBefore
SymptomEntry(id, timestamp, zoneId, type: SymptomType, severity: 0..10, note?)
MedicationIntake(id, timestamp, zoneId, name, effect: MedEffect?, note?)
DailyCheckIn(date: LocalDate PK, stress: 1..5, sleep: 1..5)
```

The timestamp is stored as UTC epoch millis plus the zone id, so travelling across time zones
keeps the day assignment correct. Settings (DataStore): reminder on/time, per-tag windows,
symptom threshold (default 4), minimum exposures (default 5).

## 6. Analysis ("Analyse" tab)

### 6.1 Day timeline
Used on Heute and in Verlauf: a chronological list with icons per entry type. Meals show items
with amounts, symptoms show severity bars, and bowel movements show the Bristol icon.

### 6.2 Trends
- Bristol type per bowel movement over time (scatter, 7-day moving median), with bands for
  1–2 (Verstopfung), 3–5 (normal) and 6–7 (Durchfall).
- Daily maximum severity per symptom (line chart, selectable symptom, 7/30/90-day range).
- Bowel movements per day.
- **Calendar heatmap**: a day's score = the highest symptom severity that day, with a Bristol 6–7 counting as 7
  and any other bowel movement as 0. Colour bands follow the severity anchors: 0, 1–3, 4–6, 7–10.
- The 7/30/90-day range applies to all three charts. Tapping a day shows its values above every chart.
  The moving median for a day covers that day and the 6 before (including days before the range); the line
  breaks where a whole week has no bowel movement.
- The heatmap always shows the last 26 weeks (Monday on top); tapping a day opens its day view.
- Antibiotic days (any `ANTIBIOTIC` intake that day) are shaded in the charts, dotted in the heatmap and get
  a pill icon in the Verlauf calendar.

### 6.3 Trigger → symptom analysis
For each tag *T* and each outcome *S*, where *S* ∈ {each SymptomType at severity ≥ threshold,
`DIARRHEA` = Bristol 6–7}:

1. **Exposures**: meals in which at least one ingredient has *T* as an effective tag. Effective means
   always-on, or a dose tag whose threshold is reached at the ingredient's effective amount (section 4.2).
   Ingredient items count directly; dish items count via their `MealItemIngredient`s.
2. **Controls**: meals with no ingredient effectively tagged *T*.
3. A meal is *positive* if at least one *S* event lies within *T*'s window after the meal time.
4. `rateExposed` = positive exposures / exposures; `rateControl` likewise for controls.
5. Show: n exposures, `rateExposed` vs. `rateControl` (as "x von n Mahlzeiten"), and the ratio.
6. Hint label:
   - **deutlicher Hinweis**: n ≥ 10, ratio ≥ 2.0 and absolute difference ≥ 25 percentage points
   - **schwacher Hinweis**: n ≥ minimum exposures (default 5) and ratio ≥ 1.5
   - **kein Hinweis**: otherwise
   - Tags with n below the minimum are shown greyed out as "zu wenig Daten (n = 3)".
7. **Medication adjustments**:
   - A meal with an `ENZYME` intake within ±30 min does **not** count as a `lactose` exposure.
     Such meals are listed separately as "mit Laktase".
   - Within 24 h after an `ANTIDIARRHEAL` intake, meals are excluded from the `DIARRHEA` outcome
     (neither exposure nor control).
   - `ANTIBIOTIC` intake is shown as a shaded period in trends and the calendar; no exclusion in v1.
8. Sorted by hint strength, then ratio. Tapping a row lists the contributing meals.

Details:
- The analysis uses all logged data. Windows include both ends.
- "mit Laktase" meals are neither exposures nor controls for `lactose`; they still count for other tags.
- The ratio is shown as "nur mit diesem Auslöser" when control meals never had the outcome, and is left out when
  there are no controls or neither group had the outcome (no hint then).
- The screen shows a "Hinweise" list (all strong and weak hints across outcomes), then all tags for one
  selected outcome. The analysis settings (threshold, minimum exposures, per-tag windows, reset) are reached
  from Einstellungen and from the bottom of this screen.

The screen always shows this disclaimer: tags co-occur in mixed meals (e.g. pizza = wheat + cheese
+ tomato), so a hint is not proof. A structured elimination diet supervised by a doctor or dietitian,
and tests such as the H₂ breath test or celiac serology, are needed to confirm anything.
The info page also notes: **don't cut out gluten before a celiac test.**

## 7. Backup
- **Export**: the complete database (all entities including the food library and its edits) plus settings
  as a single versioned JSON file, written via the Storage Access Framework (`ACTION_CREATE_DOCUMENT`).
  Default filename `logmygut-backup-YYYY-MM-DD.json`.
- **Import**: replaces all data after a confirmation dialog, with schema version check and validation.
  If anything fails, nothing changes (single transaction).
- Settings show the date of the last export, with a hint if it is older than 30 days.
- **File format** (`format: "logmygut-backup"`, `version: 1`): one JSON object with `exportedAt`, `appVersion`,
  `settings` and one array per table, rows keeping their ids. Times are ISO-8601 instants, tags use their keys,
  other enums their names. It has its own classes (`data/backup/BackupFile.kt`), so a database change doesn't change
  the format by accident; a new format version must keep reading older ones.
- **Settings in the backup:** reminder on/time, symptom threshold, minimum exposures and tag windows. Values not set
  in the backup return to their defaults. The last-export date and the "permission asked" flag stay as they are.
- **Checks before import:** format and version first (a file from a newer app is reported as such), then values
  and references (ids, foods and their hierarchy, tags, ranges). A file that passes but still fails in the database
  leaves the database unchanged. The error dialog names the problem in German, with a technical detail below.
- The hint shows when there was never an export or the last one is more than 30 days old.

## 8. Info pages (German)
- Bristol scale with illustrations and descriptions.
- Explanation of each symptom and how to rate 0–10 (anchors: 0 none, 1–3 mild, 4–6 moderate, 7–10 severe).
- Trigger tags: what they are, typical foods, related tests.
- Note on IBS (Rome IV): recurring abdominal pain related to defecation and/or a change in stool frequency or form.
  This is why the app records pain before a bowel movement and how it changed afterwards.
- Disclaimer.
- Sources: credit the Bundeslebensmittelschlüssel 4.0 (Max Rubner-Institut) and Monash University FODMAP research.

Implementation:
- Five pages, listed under Einstellungen → Info: Bristol scale, rating symptoms, triggers (with each tag's default
  window), IBS / Rome IV (including warning signs that need a doctor soon), and disclaimer, privacy and sources.
  A "?" in the Stuhlgang and Beschwerden editors and two links in the Auslöser tab open the matching page.
- The texts live in `res/values/strings_info.xml`.
- The Bristol illustrations are single-colour vector drawables generated by `tools/icons/build_bristol.py`
  (edit the script, not the XML), tinted brown at runtime. They appear in the picker, the timeline and the info page.
- The launcher icon is an adaptive icon (stomach with a check mark) with a monochrome layer for themed icons;
  its source is `tools/icons/app_icon.svg`.

## 9. Out of scope for v1
Water/tea counter, photos, PDF doctor report, CSV export, menstrual cycle, barcode scanning,
nutrition values, cloud sync, app lock, multiple users, widgets, English UI.

## 10. Quality
- Unit tests: the analysis engine (pure Kotlin, no Android dependencies) including dose thresholds and
  dish expansion, keyword tag suggestion,
  seed import, backup round-trip (export → import → equal).
- Room migrations from v1 onwards with exported schemas (`exportSchema = true`).
- A couple of Compose UI tests for the logging flows.

## 11. Milestones
Each milestone ends with a building app, passing tests and a review by the author.

0. **Setup**: install Android Studio and SDK, enable USB debugging on the phone, create the project skeleton
   (Compose, Room, DataStore, Hilt or manual DI), and an empty app running on the phone.
1. **Data layer**: entities, DAOs, repository, seed import, tag suggestion, unit tests.
2. **Logging**: Heute screen, Essen / Stuhlgang / Beschwerden / Medikament flows, edit/delete.
3. **History**: Verlauf calendar, day timeline, daily check-in.
4. **Reminder & library**: daily notification, settings screen basics, "Lebensmittel & Gerichte" library screen (4.1).
5. **Analysis**: trends, heatmap, trigger engine and its UI.
6. **Backup**: JSON export/import.
7. **Info pages and polish**: Bristol illustrations, explanations, disclaimer, app icon.
