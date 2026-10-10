# AAPS with Garmin steps

This is a fork of [AAPS](https://github.com/nightscout/AndroidAPS) with changes to the
**Garmin plugin** only. Everything else is AAPS `4.0.0-beta1`, unchanged.

> Use it at your own risk.

**Branch:** `GarminSteps-V4.0.0-beta1` (AAPS `4.0.0-beta1` + steps from a Garmin watch).
**Also with push to the watch:** branch
[`GarminPush+Steps-V4.0.0-beta1`](https://github.com/Pwb0186/AndroidAPS/tree/GarminPush+Steps-V4.0.0-beta1)
has the same steps code plus push-to-pull (V2) for watch faces.

## What it adds

### Steps from the watch
A watch face can send today's step total on `/get` (`steps=<total>`, what it reads from
`ActivityMonitor`). AAPS stores the steps in the same way as the Wear OS app, so they:

- show in the steps graph in AAPS,
- can be used by the Automation trigger **"Steps count"** (5, 10, 15, 30, 60 and
  180 minutes).

For each reading AAPS stores one record per window (5, 10, 15, 30, 60, 180 min) with
the steps in that window. Rules for the total:

- The first value is only a starting point. Nothing is stored.
- More than 12 minutes since the last value: only the starting point moves (we do not
  know when those steps were taken).
- At local midnight the watch resets its counter. This is handled.
- A lower value on the same day (for example a new watch, or a second watch sending
  to the same AAPS): only the starting point moves.
- Only one watch should send steps to an AAPS. A second watch with a higher total is
  counted as steps. When you switch watch, wait at least 12 minutes after the last
  steps were sent, or turn off "Send steps to AAPS" in the watch face settings.
- A delta of 0 steps is stored too, so a rule like "fewer than 100 steps" works.
- The steps of a reading are spread evenly over the time since the last reading.
- A window is only stored when the history covers all of it. After a start, a long
  gap or an AAPS restart, the 5 minute window comes after 5 minutes and the 180 minute
  window after 3 hours. Until then the trigger finds no record for that window, never
  a count that is too low.

Limit: the watch sends steps about every 5 minutes, and the "Steps count" trigger only
looks at records from the last 5 minutes. When the watch fetches late, the trigger
finds no record for a short time.

### Small fix
- `/get` without heart rate no longer logs "average heart rate 0 BPM 1970-01-01".

## What is not changed
Watch faces, data fields and `/sgv.json` clients work as before. A watch face that does
not send `steps=` is not affected. Wear OS, the loop, pumps and all other plugins are
plain AAPS `4.0.0-beta1`.

## How to use it
1. Build the APK from branch `GarminSteps-V4.0.0-beta1` the same way as AAPS
   (see [Building the APK](https://wiki.aaps.app/en/latest/SettingUpAaps/BuildingAaps.html)).
2. In AAPS, enable **Garmin** under Configuration → Communication. Its settings are under Garmin → Settings.
3. Install a watch face that sends steps on `/get`, for example the
   ["AAPS Push" watch face](https://github.com/Pwb0186/aaps-garmin-push-watchface).
   On the watch face, "Send steps to AAPS" must be on to send steps.
   With this branch the watch face gets its data on its own timer (no push).

## Tested
- Forerunner 955 with my own watch face.
- Tested: midnight, long gaps, watch out of range, AAPS restart, Automation rules on
  steps ("5 min ≥ 100", "10 min < 100", 60 and 180 minute windows over a night).
- Host tests: `./gradlew :plugins:sync:testAndroidHostTest`.

## Files
All changes are in `plugins/sync/src/androidMain/kotlin/app/aaps/plugins/sync/garmin/`
and the tests next to it. New files: `GarminSteps.kt` (steps) and `GarminQuery.kt`
(reads the values in the request). `GarminPlugin.kt` only calls `GarminSteps` from
`/get`; `LoopHub`/`LoopHubImpl` store the records.

## Credits
- The Garmin plugin is by the AAPS developers.
- The idea of turning the watch's daily step total into steps since the last reading,
  and the two preference names for the last total, come from MTR's OpenApsAIMI and
  swissalpine's forks. The rest of the steps code is new.

---

# Original AAPS README

## AAPS
* Check the wiki: https://wiki.aaps.app
* Want to contribute? Please read [CONTRIBUTING.md](CONTRIBUTING.md) first — note that AI-generated pull requests are not welcome.
*  Everyone who’s been looping with AAPS needs to fill out the form after 3 days of looping  https://docs.google.com/forms/d/14KcMjlINPMJHVt28MDRupa4sz4DDIooI4SrW0P3HSN8/viewform?c=0&w=1

[![Support Server](https://img.shields.io/discord/629952586895851530.svg?label=Discord&logo=Discord&colorB=7289da&style=for-the-badge)](https://discord.gg/4fQUWHZ4Mw)

[![CircleCI](https://circleci.com/gh/nightscout/AndroidAPS/tree/master.svg?style=svg)](https://circleci.com/gh/nightscout/AndroidAPS/tree/master)
[![Crowdin](https://d322cqt584bo4o.cloudfront.net/androidaps/localized.svg)](https://translations.aaps.app/project/androidaps)
[![Documentation Status](https://readthedocs.org/projects/androidaps/badge/?version=latest)](https://wiki.aaps.app/en/latest/?badge=latest)
[![codecov](https://codecov.io/gh/nightscout/AndroidAPS/branch/master/graph/badge.svg?token=EmklfIV6bH)](https://codecov.io/gh/nightscout/AndroidAPS)

DEV: 
[![CircleCI](https://circleci.com/gh/nightscout/AndroidAPS/tree/dev.svg?style=svg)](https://circleci.com/gh/nightscout/AndroidAPS/tree/dev)
[![codecov](https://codecov.io/gh/nightscout/AndroidAPS/branch/dev/graph/badge.svg?token=EmklfIV6bH)](https://codecov.io/gh/nightscout/AndroidAPS/tree/dev)

<img src="https://cdn.iconscout.com/icon/free/png-256/bitcoin-384-920569.png" srcset="https://cdn.iconscout.com/icon/free/png-512/bitcoin-384-920569.png 2x" alt="Bitcoin Icon" width="100">

3KawK8aQe48478s6fxJ8Ms6VTWkwjgr9f2
