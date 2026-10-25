# LogMyGut — Specification v1

A private, fully offline Android app for logging food, bowel movements and digestive
symptoms. It shows which food triggers occur together with symptoms in the user's own entries.
Single user (the author), sideloaded. Repo, code and identifiers are in English; the UI is in English and Austrian German.

> LogMyGut is a self-observation tool, not a diagnostic device. All analysis
> results describe the user's own entries, to discuss with a doctor.

### Intended purpose and wording (EU MDR)

Software whose intended purpose is to diagnose, monitor, predict or treat a disease is a medical device under
EU 2017/745 (MDR, rule 11: at least class IIa). The intended purpose follows from the app's texts, store listing
and other descriptions, so all of them keep to this one:

> A diary for food, drinks, bowel movements and symptoms. It shows the user's own entries as overviews and counts
> how often symptoms were logged after meals with certain trigger tags. It does not diagnose, detect diseases or
> intolerances, or recommend treatment or diet changes. The overviews are meant for talking to a doctor or dietitian.

Wording rules for UI texts, info pages and any store listing:
- Analysis results describe frequencies in the entries ("deutlich häufiger"), never causes, suspicions or
  diagnoses ("Verdacht", "verursacht", "du verträgst … nicht", "Unverträglichkeit erkannt").
- No result points to a particular test or diet. General information about tests stays on the info pages,
  apart from the analysis results.
- The app never says whether diagnostic criteria (e.g. Rome IV) are met. The IBS page is general information.
- No alarm styling (error colours, warning icons) on analysis results. Warning signs that need a doctor
  (blood in the stool etc.) are general information and stay on the IBS page.
- The full purpose statement is on the info page "Wofür LogMyGut gedacht ist", which the analysis tab links to.

## 1. Platform & constraints

| Item | Decision |
|---|---|
| Language / UI | Kotlin, Jetpack Compose (Material 3) |
| Persistence | Room (SQLite) for data, Jetpack DataStore for settings |
| Min / target SDK | 34 (Android 14) / latest stable |
| Package id | `at.mtr.logmygut` |
| App name | LogMyGut |
| Distribution | Sideload: debug builds via Android Studio / adb, release APKs handed out directly. No Play Store. |
| Licence | Code GPL-3.0-or-later (`LICENSE`); food data and keyword rules CC BY 4.0 (`LICENSES/CC-BY-4.0.txt`); the BLS extract is the MRI's, also CC BY 4.0, credited in the README and on the info page. |
| Network | **None.** No `INTERNET` permission in the manifest. No analytics, crash reporting or cloud backup (`android:allowBackup="false"`). |
| App lock | None (relies on the device lock screen) |
| Localization | **English** (default, `res/values/`) and **Austrian German** (`res/values-de/`), following the phone's language or Android's per-app language setting; other languages get English. German uses Austrian words (Semmel, Topfen, Faschiertes …) with standard German food names as search aliases; where the Austrian word is less common (Paradeiser, Erdäpfel), the food uses the standard name and keeps the Austrian one as an alias. Dates follow the language; German always formats as `de-AT` (e.g. "Jänner"). |

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

1. **Heute**: quick-add buttons (Essen & Trinken, Stuhlgang, Beschwerden, Medikament), today's fluid total with
   "+ Glas Wasser" (3.2), and today's timeline. Shows a "Tages-Check-in" card while part of today's check-in is missing.
2. **Verlauf**: month calendar (days coloured by a "bad day" score, see 6.2). Tapping a day opens its day view:
   the same quick-add buttons and fluid row as Heute, that day's check-in (editable) and timeline. Entries added
   there default to that day at the current time of day, so forgotten entries can be added later. Days with only
   meals or medication have no score and show a dot. Weeks start on Monday; future days and months can't be opened. Day boundaries use the phone's current time zone.
3. **Analyse**: trends and trigger analysis (section 6).
4. **Einstellungen**: reminder, analysis parameters, **Lebensmittel & Gerichte** (section 4.1), export (7.1), backup, info pages.

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
  - as an **ingredient**: tags are suggested from `app/src/main/assets/keyword_rules.de.json` and `keyword_rules.en.json`
    (both apply, whatever the language) and shown pre-selected;
    the user confirms or edits them;
  - as a **dish**: the user picks its ingredients from the same search; missing ingredients can be
    created inline.
- **Amount per item** depends on the item's `unit`:
  - `count`: integer stepper, default 1, label from `unitLabel` (e.g. "2 Scheiben" / "2 slices"). The built-in labels
    (Stück, Scheibe, Glas, Tasse, Dose, Flasche, Kugel, Portion) are stored as these German words, which act as keys,
    and shown translated; a label typed in either language ("slice", "Gläser") is saved as its key. Other labels are
    the user's own and stay as typed.
  - `size`: S / M / L chips, default M.
- **Zutaten anpassen** on a dish item: remove or add ingredients for this meal only.
  The saved dish stays unchanged. A checkbox **"Auch im Gericht dauerhaft speichern"** (off by default)
  also writes the change into the saved dish, so it applies from now on. Past meals are not touched.
- Meal flag: **auswärts gegessen** (restaurant/canteen; boolean).
- The timeline shows the time and what was eaten. There is no Frühstück/Mittagessen label: guessed from the time,
  it was often wrong (a snack after dinner became "Abendessen").
- **Drinks** are logged the same way ("Essen & Trinken"), because many carry triggers (coffee, beer, milk).
  An ingredient or dish can be a drink (`drink`): its portion is then millilitres ("1 Tasse · ≈ 200 ml"), it has a
  cup icon, and an entry of only drinks shows a cup in the timeline. Foods counted in Glas, Tasse, Dose or Flasche
  are drinks by default; the library editor can change it.
- **Fluid total** on Heute: the millilitres of all drinks logged today, alcoholic ones included. **"+ Glas Wasser"**
  logs 250 ml of the built-in "Wasser" in one tap; further taps raise the count of today's water-only entry
  instead of adding a row per glass.
- Entries of only drinks without any trigger (water) are left out of the trigger analysis (6.3): as "meals without
  trigger X" they would make every tag look worse than it is.

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
- Once per calendar day: **Schlaf letzte Nacht** 1–5 and **Stress** 1–5, answered separately (sleep usually in the
  morning, stress in the evening). Tapping a value saves it right away; an answered row shrinks to its value with
  "Ändern".
- Prompted by the daily reminder and the card on "Heute", which stays until both are answered. Editable afterwards
  from the day view.

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

- Seeded from `app/src/main/assets/foods.json`, which is **generated** from `tools/foods/foods.src.txt` (German) and
  `tools/foods/foods.en.txt` (English, keyed by the German name) by `tools/foods/build_seed.py` (see 4.5). About 380
  ingredients and 60 dishes, with Austrian names and standard German aliases, English names and aliases (`en`),
  `unit`, `unitLabel`, and for ingredients `grams`, `tags`, optional `doseTags`, `parent`, `removeTags` and `note`.
  Dishes list their `ingredients` by seed key. Within each language, a name or alias belongs to one food.
- On every app start, after a language change and after a backup import, the importer syncs the built-in foods with
  the seed (cheap when nothing changed): missing entries are added; built-in entries the user hasn't changed get the
  seed's current name, aliases, note, tags, parent or recipe. The unit of an existing food never changes, because
  logged meal items depend on it.
- **Names follow the language.** Built-in foods are stored with the name, aliases and note in the app's language;
  the names and aliases in the other language are added as aliases, so search finds a food in either language.
  Meals refer to foods by id and the analysis uses tags, so switching languages, or importing a backup made in the
  other language, only renames the built-in foods.
- Users can edit any ingredient (tags, dose thresholds, unit, aliases, parent) or dish (ingredient list), create new ones,
  and hide built-in ones. Editing sets `userModified`; such entries keep everything the seed would change, except that
  their name still follows the language as long as it is one of the seed's names (or the seed key). A food the user
  renamed keeps its name. User-created foods are never touched by the seed.
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
  ("Tomate" with „Paradeiser“).
- The analysis stays tag-based. Later, per-food views may roll children up into their parent.

### 4.5 Seed data sources

- **Nutrient tags** (lactose, fructose, polyols, fat, alcohol) are computed from the **Bundeslebensmittelschlüssel
  (BLS) 4.0** (Max Rubner-Institut 2025, DOI 10.25826/Data20251217-134202-0), licensed under CC BY 4.0.
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
     searchText, lastUsedAt: Instant?, parentId?,       -- searchText = normalized "name|alias|…", set on every write and refreshed on start; lastUsedAt for ranking
     portionGrams: Double?, drink: Boolean)             -- grams of an M portion / one piece (ml for drinks); null if unknown
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
DailyCheckIn(date: LocalDate PK, stress: 1..5?, sleep: 1..5?) -- answered separately, at least one set
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
   - **deutlich häufiger** (`STRONG`): n ≥ 10, ratio ≥ 2.0 and absolute difference ≥ 25 percentage points
   - **etwas häufiger** (`WEAK`): n ≥ minimum exposures (default 5) and ratio ≥ 1.5
   - **nicht auffällig** (`NONE`): otherwise
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
- Entries of only drinks without any trigger (a glass of water) are not meals and are left out entirely (3.2).
- The ratio is shown as "nur mit diesem Auslöser" when control meals never had the outcome, and is left out when
  there are no controls or neither group had the outcome (no hint then).
- The tab is called "Zusammenhänge". It shows an "Auffällig in deinen Einträgen" list (all strong and weak hints
  across outcomes), then all tags for one
  selected outcome. The analysis settings (threshold, minimum exposures, per-tag windows, reset) are reached
  from Einstellungen and from the bottom of this screen.

The screen always shows this disclaimer: it shows only what occurs together in the entries, not what causes
symptoms; tags co-occur in mixed meals (e.g. pizza = wheat + cheese + tomato) and symptoms often have other
reasons such as stress or sleep; the app does not diagnose; take the overview to a doctor or dietitian to look
into causes; **don't cut out gluten before a celiac test.** Below it, links to the trigger info page and the
purpose statement. Badges use neutral colours (see Intended purpose).

## 7. Backup
- **Export**: the complete database (all entities including the food library and its edits) plus settings
  as a single versioned JSON file, written via the Storage Access Framework (`ACTION_CREATE_DOCUMENT`).
  Default filename `logmygut-backup-YYYY-MM-DD.json`.
- **Import**: replaces all data after a confirmation dialog, with schema version check and validation.
  If anything fails, nothing changes (single transaction).
- Settings show the date of the last export, with a hint if it is older than 30 days.
- **File format** (`format: "logmygut-backup"`, `version: 2`): one JSON object with `exportedAt`, `appVersion`,
  `settings` and one array per table, rows keeping their ids. Times are ISO-8601 instants, tags use their keys,
  other enums their names. It has its own classes (`data/backup/BackupFile.kt`), so a database change doesn't change
  the format by accident; a new format version must keep reading older ones.
- Version 2 added the foods' `drink` flag and optional check-in values. Version 1 files still import; their
  drinks follow from the unit (Glas, Tasse, Dose, Flasche), as in the database migration to v5.
- **Settings in the backup:** reminder on/time, symptom threshold, minimum exposures and tag windows. Values not set
  in the backup return to their defaults. The last-export date and the "permission asked" flag stay as they are.
- **Checks before import:** format and version first (a file from a newer app is reported as such), then values
  and references (ids, foods and their hierarchy, tags, ranges). A file that passes but still fails in the database
  leaves the database unchanged. The error dialog names the problem in the app's language, with a technical detail below.
- The hint shows when there was never an export or the last one is more than 30 days old.

### 7.1 Report and CSV export
Under Einstellungen → Export, for taking entries to a doctor or dietitian, or into a spreadsheet. Both can be saved
(Storage Access Framework) or shared (Android share sheet via a `FileProvider` for the cache folder `exports/`, which
holds only the latest export). The dialog says the file contains health data. Unlike the backup, neither file can be
imported again.

- **PDF report** ("Bericht für den Arztbesuch") for the last 14, 30 or 90 days or all entries, in the app's language,
  A4. It describes the entries and interprets nothing (see Intended purpose):
  - header: period, creation date and a short purpose statement (diary, not a medical device);
  - overview: number of food & drink entries; bowel movements (total, per day, per Bristol type, urgent, cramps before
    and how they changed); per symptom the days it was logged, the days it reached the analysis threshold and its
    highest value; medication by name (intakes, days); average sleep and stress from the check-ins;
  - "Auffällig in deinen Einträgen": the strong and weak hints (6.3) computed from the period's meals only, with the
    same wording and numbers as the Zusammenhänge tab, the parameter summary and the disclaimer;
  - entries by day: each day with entries, its check-in values and fluid total, then every entry with its time and
    note. Meal items show amount and ml; dish items list the meal's actual ingredients.
  - Footer "LogMyGut · Seite n".
- **CSV** ("Alle Einträge als CSV") of all entries: RFC 4180, comma-separated, CRLF, UTF-8 with byte order mark.
  English column names `date, time, zone, entry, meal, name, kind, amount, unit, ml, ingredients, value, urgent,
  pain_before, pain_after, eaten_out, effect, note`. One row per meal item (`meal` = the meal's id, so items group),
  bowel movement (`value` = Bristol type), symptom (`name` = symptom key, `value` = severity), medication and
  check-in value (`entry` = `check_in`, `name` = `sleep`/`stress`, no time). Date and time are in the zone the entry
  was logged in; enum values are lower-case keys; food names are as stored.

## 8. Info pages (English and German)
- Bristol scale with illustrations and descriptions.
- Explanation of each symptom and how to rate 0–10 (anchors: 0 none, 1–3 mild, 4–6 moderate, 7–10 severe).
- Trigger tags: what they are, typical foods, related tests.
- Note on IBS (Rome IV): recurring abdominal pain related to defecation and/or a change in stool frequency or form,
  as general information. The page says which entries help in talking to a doctor, and that the app does not
  check these criteria.
- Purpose statement ("Wofür LogMyGut gedacht ist"), see Intended purpose.
- Sources: credit the Bundeslebensmittelschlüssel 4.0 (Max Rubner-Institut) and Monash University FODMAP research.

Implementation:
- Five pages, listed under Einstellungen → Info: Bristol scale, rating symptoms, triggers (with each tag's default
  window), IBS / Rome IV (including warning signs that need a doctor soon), and purpose statement, privacy,
  sources and licence. A "?" in the Stuhlgang and Beschwerden editors and two links in the Zusammenhänge tab (triggers, purpose)
  open the matching page.
- The texts live in `res/values/strings_info.xml`.
- The Bristol illustrations are single-colour vector drawables generated by `tools/icons/build_bristol.py`
  (edit the script, not the XML), tinted brown at runtime. They appear in the picker, the timeline and the info page.
- The launcher icon is an adaptive icon (stomach with a check mark) with a monochrome layer for themed icons;
  its source is `tools/icons/app_icon.svg`.

## 9. Out of scope for v1
Photos, menstrual cycle, barcode scanning,
nutrition values, cloud sync, app lock, multiple users, widgets.
(A fluid total came in after all, as part of drink logging: 3.2, and so did an English UI, a PDF report and a CSV
export: 7.1.)

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

## 12. Backlog
Open points for later, roughly in order. Not part of any milestone yet.

- **Purpose statement on first launch** (Intended purpose, EU MDR): show the purpose statement once, before the
  first entry, with an "Verstanden" button; remember that it was shown (DataStore, not in the backup). Optional
  for now; worth adding before a store listing.
- **Publishing on F-Droid**: done so far: `dependenciesInfo { includeInApk = false }`, Fastlane metadata in German and
  English with screenshots - **Publishing on F-Droid**: `dependenciesInfo { includeInApk = false }`, Fastlane metadata in German and English,
  decide on signing.
