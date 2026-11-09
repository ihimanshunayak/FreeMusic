# Date-Based Version System

Status: design (awaiting build/install verification)
Scope: `versionName` + `versionCode` for both `dev` and `prod` flavors,
both `debug` and `release` build types.

## Format

| Field      | Value                              | Example          |
|------------|------------------------------------|------------------|
| versionName | `DD.MM.YY` (1st same-day build) or `DD.MM.YY.N` (Nth) | `16.10.26` / `16.10.26.2` |
| versionCode | `YYYYMMDD * 100 + N` (N capped at 99) | `20261016` / `2026101602` |

- `DD`, `MM`, `YY` are zero-padded to 2 digits, system clock, local time.
- First same-day build: no `.N` suffix, counter is implicitly `1`.
- Subsequent same-day build: `.2`, `.3`, ... (counter increments).
- New day: counter resets to 1, date rolls over automatically.
- Optional `betaSuffix` (existing mechanism, e.g. `beta2`) is still appended
  after the date version with a `-` so `AppUpdateChecker.parseVersion` keeps
  treating it as a pre-release: `16.10.26-beta2`.

## How the counter is tracked

A per-machine, per-project file: `.gradle/version-date.properties`
(ignored by git — the same way `local.properties` is ignored). Contents:

```
date=2026-10-16
counter=2
```

Read at configuration time, written after a successful build. If the file
is missing, both default to "today" and `counter = 0`, and the next build
becomes the first of the day.

## Why `YYYYMMDD * 100 + N` for versionCode

- Monotonically increasing across days and within a day.
- Stays well under `Int.MAX_VALUE` for decades (capped at 99 same-day).
- Plays nice with Play Store: they require strictly-increasing `versionCode`,
  and this gives one slot per minute-level collision case.
- Pure date math, no parsing, no string compare — just `>` on Int.

## Update-check comparison semantics

`AppUpdateChecker` parses a version name into two possible shapes:

- **Calendar-date version** — at least 3 numeric parts where part 1 is a
  plausible day (`1..31`) and part 2 is a plausible month (`1..12`),
  e.g. `08.11.26` or `16.10.26.2`. The trailing counter (if present) is
  the same-day build number.
- **Legacy numeric version** — everything else, e.g. the historical
  `1.20` scheme.

Comparison then follows three rules:

1. **Date vs date** — compared as a real calendar date:
   year, then month, then day, then the same-day counter. This is what
   makes `08.11.26` (8 Nov) correctly outrank `28.10.26.19` (28 Oct),
   which a plain dotted-numeric compare got wrong.
2. **Date vs legacy** — a date-based release is always newer than a legacy
   `1.N` build, and a legacy release is never newer than a date-based
   build. The date scheme was introduced after the legacy scheme.
3. **Legacy vs legacy** — compared part by part as plain integers, exactly
   as before (existing behaviour preserved).

Pre-release handling is unchanged and applies on both paths: a `-betaN`
suffix marks a pre-release, and a plain release with the same numbers
outranks it.

> **History:** before the date-aware comparison landed, `isNewer()` compared
> every dotted part numerically. That made any October build look newer than
> the following November release, so the in-app update prompt silently never
> appeared for those users. The fix is covered by
> `app/src/test/java/com/ihimanshunayak/freemusic/AppUpdateVersionCompareTest.kt`.

Existing `betaSuffix` (env `BETA_SUFFIX` / `-PbetaSuffix=...`) keeps
working as a pre-release marker; date-version is the *base* name now.

The GitHub release tag (`v1.20`) is decoupled from `versionName` — it
remains human-curated (`v16.10.26`, `v16.10.26-2`, etc.) so the visible
changelog still goes up in a sensible order.

## What still ships manually

- The release *tag* (e.g. `v16.10.26`).
- The release *notes*.
- The commit message.
- The beta suffix, if the build is a sideloaded test.

None of these are version-number decisions; the version itself is fully
derived from the clock.
