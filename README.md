# AAPS with Garmin push

This is a fork of [AAPS](https://github.com/nightscout/AndroidAPS) with changes to the
**Garmin plugin** only. Everything else is AAPS `4.0.0-rc1`, unchanged.

> Use it at your own risk.

**Branch:** `GarminPush-V4.0.0-rc1` (AAPS `4.0.0-rc1` + push-to-pull for watch faces, no steps).
**Watch face:** [aaps-garmin-push-watchface](https://github.com/Pwb0186/aaps-garmin-push-watchface),
a Connect IQ watch face that uses all of this.
**Other branches:**
[`GarminPush+Steps-V4.0.0-rc1`](https://github.com/Pwb0186/AndroidAPS/tree/GarminPush+Steps-V4.0.0-rc1)
has push and steps from the watch,
[`GarminSteps-V4.0.0-rc1`](https://github.com/Pwb0186/AndroidAPS/tree/GarminSteps-V4.0.0-rc1)
has only steps, without push.

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

### 2. Smaller fixes in the Garmin plugin
- A message that gets no answer from the watch within 20 s is sent once more, or
  dropped if a newer message waits. Before, one lost message blocked the queue for
  that app. Old V1 messages are not sent again.
- Calls to Garmin Connect that can throw (for example while it updates) are caught and
  logged instead of crashing AAPS.
- Query values are decoded once. Before, an AAPS key with `+` or `%` never matched.
- The AAPS key is masked in the log (`key=***`).
- `/get` without heart rate no longer logs "average heart rate 0 BPM 1970-01-01".
- Heart rate is stored with the end of the measuring period as time, as AAPS expects.
  Before, it was stored 5 minutes too early.

## What is not changed
V1 watch faces, data fields and `/sgv.json` clients work as before. With no push watch
face and no AAPS key, AAPS does not connect to Garmin Connect, as before.
Wear OS, the loop, pumps and all other plugins are plain AAPS `4.0.0-rc1`.

## How to use it
1. Build the APK from branch `GarminPush-V4.0.0-rc1` the same way as AAPS
   (see [Building the APK](https://wiki.aaps.app/en/latest/SettingUpAaps/BuildingAaps.html)).
2. In AAPS, enable **Garmin** under Configuration → Communication. Its settings are under Garmin → Settings.
3. Install a watch face that uses V2, for example the
   ["AAPS Push" watch face](https://github.com/Pwb0186/aaps-garmin-push-watchface).
   This branch does not store steps, so "Send steps to AAPS" on the watch face can stay off.

## Tested
- Forerunner 955 with my own watch face.
- The push code is the same as on `GarminPush+Steps-V4.0.0-rc1`, which runs daily.
- Tested: 24 hours, switching between 6 watch faces, Bluetooth off, phone restart,
  Garmin Connect force stop, watch out of range (6 and 24 min), carbs entered,
  AAPS key set, plugin quickly off/on, midnight.
- Host tests: `./gradlew :plugins:sync:testAndroidHostTest`.
- Watch battery: about 7 %/day with V2, against 6 % with the old timer-only watch face.

## Files
All changes are in `plugins/sync/src/androidMain/kotlin/app/aaps/plugins/sync/garmin/`
and the tests next to it. New files: `GarminV2Push.kt` (registered push apps)
and `GarminQuery.kt` (query parser).

## Credits
- The Garmin plugin is by the AAPS developers.

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
