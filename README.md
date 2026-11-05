# AAPS Garmin plugin: push-to-pull for watch faces

This branch changes the Garmin plugin only. Everything else is AAPS `dev`.
Use it at your own risk.

## Push-to-pull (V2)
A watch face can ask AAPS for data on a timer. Then the data can be up to 5 minutes old.
With push-to-pull, AAPS tells the watch when there is something new:

1. AAPS sends a very small "update now" message to the watch (`{key, command: "updateWatch"}`).
2. The watch gets the data itself from `/get`.
3. The watch shows new values a few seconds after AAPS has them.

How it works:

- A watch face registers when it sends its Connect IQ app id: `/get?appId=<32 hex digits>`.
  There is no fixed list of app ids. Old watch faces work as before.
- AAPS remembers at most 10 apps. It pushes only to apps that called `/get` in the last 15 minutes.
- AAPS pushes on a new glucose value. It also pushes after a bolus, carbs, temp target,
  temp basal, extended bolus or running mode change. It waits 3.5 s to group changes.
  There is at most one push every 3 s.
- AAPS connects to Garmin Connect only if an AAPS key is set, or if a push watch face
  called `/get` in the last 15 minutes.
- The AAPS key is hidden in the log (`key=***`).

## Fields in `/get`
In addition to the old fields, `/get` returns:

- `carbsOnBoard`: left out when AAPS has no COB yet, so the watch keeps its last value.
- `loopEnabled`: true if the loop is enabled and running.
- `temporaryTargetActive`, and if one is active: `temporaryTargetLow`, `temporaryTargetHigh`,
  `temporaryTargetReason`, `temporaryTargetDurationMin`, and `temporaryTargetEndSec`
  (left out when the temp target has no end).
- Pump status:
  - `reservoir`: units in the reservoir. Left out when unknown, and for a patch pump at its
    maximum reading (for example "50+").
  - `reservoirLevel`: 0 = ok, 1 = warning, 2 = critical.
  - `cannulaChangeSec`: time of the last cannula (or pod) change, in seconds since 1970.
    The watch can count the age itself.
  - `cannulaWarnH`, `cannulaCritH`: warning and critical age in hours.
    The limits are the same as in the AAPS overview.
- `timestamp`: time of the answer, in seconds since 1970.

`wait=` (long poll) is limited to 120 s.

## Other branches
These branches are on the fork [Pwb0186/AndroidAPS](https://github.com/Pwb0186/AndroidAPS):

- [`GarminSteps-dev`](https://github.com/Pwb0186/AndroidAPS/tree/GarminSteps-dev): steps from the watch.
- [`GarminControl-dev`](https://github.com/Pwb0186/AndroidAPS/tree/GarminControl-dev): commands from the watch (temp target, scenes, bolus).
- [`GarminPush+Steps+Control-dev`](https://github.com/Pwb0186/AndroidAPS/tree/GarminPush+Steps+Control-dev): all of them together.

Watch face that uses push-to-pull:
[aaps-garmin-push-watchface](https://github.com/Pwb0186/aaps-garmin-push-watchface).

---

# AAPS
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
