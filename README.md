# AAPS with Garmin steps

This is a fork of [AAPS](https://github.com/nightscout/AndroidAPS). It changes the
**Garmin plugin** only. Everything else is AAPS `dev`.

> Use it at your own risk. It is only tested with the host tests.

**Branch:** `GarminSteps-dev` (AAPS `dev` and one commit).

**Other branches on this fork:**
- [`GarminPush-dev`](https://github.com/Pwb0186/AndroidAPS/tree/GarminPush-dev):
  push-to-pull for watch faces.
- [`GarminControl-dev`](https://github.com/Pwb0186/AndroidAPS/tree/GarminControl-dev):
  commands from the watch (temp target, scenes, bolus).
- [`GarminPush+Steps+Control-dev`](https://github.com/Pwb0186/AndroidAPS/tree/GarminPush+Steps+Control-dev):
  all of them together.

## What it adds

### Steps from the watch
A watch face can send the step total of today on `/get` (`steps=<total>`). The watch
reads it from `ActivityMonitor`. AAPS stores the steps in the same way as the Wear OS
app. So the steps:

- show in the steps graph in AAPS,
- can be used by the Automation trigger **"Steps count"** (5, 10, 15, 30, 60 and
  180 minutes).

For each reading AAPS stores one record per window (5, 10, 15, 30, 60 and 180 minutes).
Each record has the steps of that window.

Rules:

- The first value is only a starting point. Nothing is stored.
- The watch face may keep its totals while AAPS cannot be reached. It sends them with
  the next request (`stepsPrev`). AAPS reads them at their own time, so there is no gap.
- If there are no kept totals and the last value is more than 12 minutes old, only the
  starting point moves. AAPS does not know when those steps were taken.
- At midnight the watch sets its counter to 0. AAPS handles this.
- A lower value on the same day only moves the starting point.
- A watch of another model starts again from a new starting point. The model is the
  part number in `device=`. Only one watch should send steps to one AAPS.
- A delta of 0 steps is stored too. So a rule like "fewer than 100 steps" works.
- A window is only stored when the history covers all of it. The 5 minute window
  comes after 5 minutes. The 180 minute window comes after 3 hours.
- The history is saved. After an AAPS restart the windows go on.

Limit: the "Steps count" trigger only looks at records from the last 5 minutes. If the
watch answers late, the trigger can find no record for a short time.

### Heart rate fixes
- Heart rate is stored with the end of the measuring period as the time, as AAPS
  expects. Before, it was stored too early.
- A `/get` without heart rate does not log "average heart rate 0 BPM 1970-01-01" any more.

## Files
The changes are in `plugins/sync/src/androidMain/kotlin/app/aaps/plugins/sync/garmin/`
and the tests next to it. New files: `GarminSteps.kt` (steps) and `GarminQuery.kt`
(reads the query of a request).

Host tests: `./gradlew :plugins:sync:testAndroidHostTest`

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
