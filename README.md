# AAPS with Garmin push and steps

This is a fork of [AAPS](https://github.com/nightscout/AndroidAPS) with changes to the
**Garmin plugin** only. Everything else is AAPS `4.0.0-beta1`, unchanged.

> Use it at your own risk.

**Branch:** `GarminPush+Steps-V4.0.0-beta1` (AAPS `4.0.0-beta1` + the Garmin changes in one commit).
**Watch face:** [aaps-garmin-demo-watchface](https://github.com/Pwb0186/aaps-garmin-demo-watchface),
a Connect IQ watch face that uses all of this.

## What it adds

### 1. Push-to-pull for watch faces (V2)
Today a Garmin watch face either asks AAPS for data on a timer (data can be up to
5 minutes old), or AAPS sends the whole data set over Connect IQ (V1).
V2 combines the two:

- AAPS sends a very small "update now" message to the watch.
- The watch then gets the data itself over HTTP (`/get`).
- The watch shows new values a few seconds after AAPS has them.

How it works:

- A watch face registers by sending its Connect IQ app id on `/get`
  (`/get?appId=<32 hex>`). There is no fixed list of app ids, and old watch faces are
  not affected.
- AAPS remembers at most 10 apps and pushes only to apps that fetched data in the last
  15 minutes. A watch face you switched away from gets a few pushes and is then
  forgotten until it fetches again.
- A push is sent on a new glucose value, and after a bolus, carbs, temp target,
  temp basal, extended bolus or running mode change (waits 3.5 s to group changes).
  At most one push per 3 s.
- The push message is only `{key, command: "updateWatch"}`.
- AAPS only connects to Garmin Connect when an AAPS key is set (as before) or a push
  watch face has fetched data in the last 15 minutes.

`/get` also returns more fields: `carbsOnBoard` (left out when AAPS has no COB yet, so
the watch keeps its last value instead of showing 0), `temporaryTarget*`,
`loopEnabled` and `timestamp`.

### 2. Steps from the watch
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
- A lower value on the same day (watch restart, other watch face): only the starting
  point moves.
- A delta of 0 steps is stored too, so a rule like "fewer than 100 steps" works.
- The steps of a reading are spread evenly over the time since the last reading.
- A window is only stored when the history covers all of it. After a start, a long
  gap or an AAPS restart, the 5 minute window comes after 5 minutes and the 180 minute
  window after 3 hours. Until then the trigger finds no record for that window, never
  a count that is too low.

Limit: the watch sends steps about every 5 minutes, and the "Steps count" trigger only
looks at records from the last 5 minutes. When the watch answers late or a glucose
value is missing, the trigger finds no record for a short time.

### 3. Smaller fixes in the Garmin plugin
- A message that gets no answer from the watch within 20 s is sent once more, or
  dropped if a newer message waits. Before, one lost message blocked the queue for
  that app. Old V1 messages are not sent again.
- Calls to Garmin Connect that can throw (for example while it updates) are caught and
  logged instead of crashing AAPS.
- Query values are decoded once. Before, an AAPS key with `+` or `%` never matched.
- The AAPS key is masked in the log (`key=***`).
- `/get` without heart rate no longer logs "average heart rate 0 BPM 1970-01-01".

## What is not changed
V1 watch faces, data fields and `/sgv.json` clients work as before. With no push watch
face and no AAPS key, AAPS does not connect to Garmin Connect, as before.
Wear OS, the loop, pumps and all other plugins are plain AAPS `4.0.0-beta1`.

## How to use it
1. Build the APK from branch `GarminPush+Steps-V4.0.0-beta1` the same way as AAPS
   (see [Building the APK](https://wiki.aaps.app/en/latest/SettingUpAaps/BuildingAaps.html)).
2. In AAPS, enable **Garmin** under Config Builder → Synchronization.
3. Install a watch face that uses V2 and/or sends steps, for example the
   [demo watch face](https://github.com/Pwb0186/aaps-garmin-demo-watchface).
   On the watch face, "Send steps to AAPS" must be on to send steps.

## Tested
- Forerunner 955 with my own watch face.
- The same logic runs daily on AAPS 3.4 (master) and on this branch.
- Tested: 24 hours, switching between 6 watch faces, Bluetooth off, phone restart,
  Garmin Connect force stop, watch out of range (6 and 24 min), carbs entered,
  AAPS key set, plugin quickly off/on, midnight, Automation rules on steps
  ("5 min ≥ 100", "10 min < 100", 60 and 180 minute windows over a night).
- Host tests: `./gradlew :plugins:sync:testAndroidHostTest`.
- Watch battery: about 7 %/day with V2, against 6 % with the old timer-only watch face.

## Files
All changes are in `plugins/sync/src/androidMain/kotlin/app/aaps/plugins/sync/garmin/`
and the tests next to it. New files: `GarminSteps.kt` (steps), `GarminV2Push.kt`
(registered push apps), `GarminQuery.kt` (query parser).

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
