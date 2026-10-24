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
| Distribution | Sideload via Android Studio + USB debugging. No Play Store. |
| Network | **None.** No `INTERNET` permission in the manifest. No analytics, crash reporting or cloud backup (`android:allowBackup="false"`). |
| App lock | None (relies on the device lock screen) |
| Localization | All UI text in `res/values/strings.xml`, in **Austrian German** (Semmel, Topfen, Erdäpfel …). Standard German food names are search aliases. Dates and times are formatted with locale `de-AT` (e.g. "Jänner"). English can be added later. |

Permissions: `POST_NOTIFICATIONS` (reminder), `SCHEDULE_EXACT_ALARM` is **not** used.
The daily reminder uses an inexact alarm or WorkManager, which is acceptable for this purpose.

## 2. Navigation

Bottom navigation with four tabs:

1. **Heute**: quick-add buttons (Essen, Stuhlgang, Beschwerden, Medikament) above today's timeline.
   Shows a "Tages-Check-in" card if today's check-in is missing.
2. **Verlauf**: month calendar (days coloured by a "bad day" score, see 6.2). Tapping a day opens its timeline.
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

## 4. Food library

Two kinds of entries:

- **Ingredient (Zutat)**: anything eaten or bought as one thing, including products whose
  recipe you don't control (Müsli, Kekse, Tomatensauce, Brühe). Ingredients carry tags.
- **Dish (Gericht)**: a named list of ingredients with no tags of its own. Its triggers are the union of its
  ingredients' (effective) tags. Dishes cannot contain dishes.

Seed data and editing:

- Seeded from `app/src/main/assets/foods.de.json`. On every app start, entries whose seed key is not yet in the database are added (cheap when nothing is missing):
  about 220 ingredients and about 40 dishes, with Austrian names and standard German aliases, `unit`, `unitLabel`, and for
  ingredients `tags`, optional `doseTags` and `note`. Dishes list their `ingredients` by name.
- Users can edit any ingredient (tags, dose thresholds, unit, aliases) or dish (ingredient list), create new ones,
  and hide built-in ones. User edits are never overwritten by a later seed version. A seed update only adds new entries.
- **Seed keys are permanent.** A seed entry's `name` is its key. Never rename an entry in the seed file once real data
  exists, or the importer adds a duplicate. Change display names in the app instead, or add a migration that rewrites `seedKey`.

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

## 5. Data model (Room)

```
Food(id, kind: INGREDIENT|DISH, name, aliases: List<String>, unit: COUNT|SIZE, unitLabel?, note?,
     builtIn: Boolean, userModified: Boolean, hidden: Boolean, seedKey?,
     searchText, lastUsedAt: Instant?)                  -- searchText = normalized "name|alias|…"; lastUsedAt for ranking
                                                        -- seedKey = the seed entry's original name (never changes)
FoodTag(foodId, tag, minSize: M|L?, minCount: Int?)     -- PK (foodId, tag); both null = always applies
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
- **Calendar heatmap**: a day's score = the highest symptom severity that day, with a Bristol 6–7 counting as 7.

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

## 8. Info pages (German)
- Bristol scale with illustrations and descriptions.
- Explanation of each symptom and how to rate 0–10 (anchors: 0 none, 1–3 mild, 4–6 moderate, 7–10 severe).
- Trigger tags: what they are, typical foods, related tests.
- Note on IBS (Rome IV): recurring abdominal pain related to defecation and/or a change in stool frequency or form.
  This is why the app records pain before a bowel movement and how it changed afterwards.
- Disclaimer.

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
