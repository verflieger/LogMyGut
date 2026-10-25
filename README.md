# LogMyGut

A private, fully offline Android diary for food, drinks, bowel movements and digestive symptoms.

> A diary for food, drinks, bowel movements and symptoms. It shows the user's own entries as overviews and counts
> how often symptoms were logged after meals with certain trigger tags. It does not diagnose, detect diseases or
> intolerances, or recommend treatment or diet changes. The overviews are meant for talking to a doctor or dietitian.

The UI is in Austrian German. The full specification is in [SPEC.md](SPEC.md).

## Features

- **Logging:** meals and drinks (ingredients or dishes, with portion sizes or piece counts), bowel movements
  (Bristol scale), symptoms (severity 0–10), medication, and a daily check-in for stress and sleep. Shows today's
  fluid total.
- **Food library:** about 380 ingredients and 60 dishes with trigger tags (lactose, fructose, polyols, fructans,
  GOS, gluten, histamine, fat, caffeine, alcohol, spicy, carbonated). Some tags apply only from a certain portion
  size. Ingredients can inherit tags from a more general food. You can edit, add and hide foods.
- **History:** month calendar coloured by each day's symptoms, with a timeline for each day.
- **Analysis:** trends (Bristol type, symptom severity, bowel movements per day), a 26-week heatmap, and for each
  trigger tag how often symptoms followed meals with and without it.
- **Daily reminder**, **JSON backup** export and import, and info pages (Bristol scale, symptom ratings, triggers,
  IBS).

## Privacy

The app has no network access: the `INTERNET` permission is removed from the manifest, and there is no analytics,
crash reporting or Android cloud backup. All data stays on the device until you export a backup yourself. The
backup file is plain JSON and contains health data.

## Building

Requirements: Android Studio with the Android SDK. The app runs on Android 14 (SDK 34) and newer.

```sh
./gradlew assembleDebug        # build a debug APK
./gradlew installDebug         # install on a connected device
./gradlew testDebugUnitTest    # run the unit and Robolectric UI tests
```

Release builds are signed with a key from `~/.config/logmygut/` that isn't in the repository. Without it,
`assembleRelease` produces an unsigned APK. Updates must always be signed with the same key, or Android refuses to
install them over the existing app.

### Food data

`app/src/main/assets/foods.de.json` is generated. Edit `tools/foods/foods.src.txt`, then run:

```sh
python tools/foods/build_seed.py
```

`tools/foods/extract_bls.py` refreshes `tools/foods/bls_subset.tsv` from the full BLS file, which isn't in the
repository (needs `openpyxl`).

## Sources

- Nutrient-based tags are computed from the **Bundeslebensmittelschlüssel (BLS) 4.0**: Max Rubner-Institut (2025):
  Bundeslebensmittelschlüssel (BLS), Version 4.0 – Deutsche Nährstoffdatenbank. Karlsruhe.
  DOI [10.25826/Data20251217-134202-0](https://doi.org/10.25826/Data20251217-134202-0). The BLS is free to use,
  including in apps ([BLS FAQ](https://www.blsdb.de/faq)).
- Curated tags follow published **Monash University FODMAP** research and commonly published histamine lists.
- The Bristol stool illustrations were drawn for this app and are not taken from the original chart.

## Licence

Copyright © 2026 verflieger

- **Code:** [GNU General Public License v3.0 or later](LICENSE) (GPL-3.0-or-later). You may use, change and share
  the app, as long as anything you distribute based on it is also published under the GPL.
- **Food data:** the food list and its tags (`tools/foods/foods.src.txt`, `app/src/main/assets/foods.de.json`) and
  the tag suggestion rules (`app/src/main/assets/keyword_rules.de.json`) are licensed under
  [CC BY 4.0](LICENSES/CC-BY-4.0.txt).
- **BLS extract:** `tools/foods/bls_subset.tsv` holds values from the BLS 4.0 and stays under the Max Rubner-Institut's
  terms; cite it as shown under [Sources](#sources).
