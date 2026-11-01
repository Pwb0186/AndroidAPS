# AAPS 4.0.0 with Garmin push, steps and watch commands

This is a fork of [AAPS](https://github.com/nightscout/AndroidAPS) with changes to the
**Garmin plugin** only. Everything else is AAPS 4.0.0 (`master`), unchanged.

> This is experimental. Do not use it to dose insulin unless you understand and accept the
> risks. Use at your own risk.

**Branch:** `GarminPush+Steps+Control-V4.0.0` (AAPS 4.0.0 + the Garmin changes).
**Watch face:** [aaps-garmin-push-watchface](https://github.com/Pwb0186/aaps-garmin-push-watchface)
uses push, steps and the extra `/get` fields.
**Watch app for the commands:** [AAPS Control widget](https://github.com/Pwb0186/aaps-garmin-control-widget),
a Connect IQ widget that you build and install yourself.

Without a control key (see [Security](#security)) every command is refused and the branch
only adds push, steps and the extra `/get` fields.

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
- While AAPS cannot be reached (phone out of range, Bluetooth off, AAPS stopped), the
  watch face keeps its totals (up to 3 hours) and sends them with the next request
  (`stepsPrev`). AAPS reads them at their own time, so there is no gap: all windows are
  right at once and the steps graph is filled in.
- Without kept totals (a watch face that does not send them, or a longer gap), more than
  12 minutes since the last value: only the starting point moves (we do not know when
  those steps were taken).
- At local midnight the watch resets its counter. This is handled.
- A lower value on the same day (for example a new watch, or a second watch sending
  to the same AAPS): only the starting point moves.
- A watch of another model (the part number the watch face sends in `device=`) starts
  again from a new starting point, so switching between, for example, a daily watch and a
  sport watch never counts the difference as steps. Two watches that send at the same
  time store no steps, so only one watch should send steps to an AAPS (turn off "Send
  steps to AAPS" on the other). Two watches of the same model cannot be told apart.
- A delta of 0 steps is stored too, so a rule like "fewer than 100 steps" works.
- The steps of a reading are spread evenly over the time since the last reading.
- A window is only stored when the history covers all of it. After the first reading or a
  long gap, the 5 minute window comes after 5 minutes and the 180 minute
  window after 3 hours. Until then the trigger finds no record for that window, never
  a count that is too low.
- The history is saved, so after an AAPS restart the windows go on (when it took more
  than 12 minutes, only with the totals the watch face kept).

Limit: the watch sends steps about every 5 minutes, and the "Steps count" trigger only
looks at records from the last 5 minutes. When the watch answers late or a glucose
value is missing, the trigger finds no record for a short time.

### 3. Pump status
`/get` also returns, when AAPS knows it:

- `reservoir`: insulin left in whole U (U100). Left out when unknown, on AAPSClient, and for
  a patch pump that only reports "50+" (Omnipod).
- `reservoirLevel`: 0 normal, 1 warning, 2 critical, with the AAPS status light thresholds.
- `cannulaChangeSec`: time of the last cannula or pod change (seconds), with `cannulaWarnH`
  and `cannulaCritH` from the AAPS status light settings. The watch counts the age itself.

### 4. Commands from the watch
Temp target, scenes, Quick Wizard, bolus wizard, treatment (insulin and/or carbs) and stop
bolus.

#### How a command works
Every command that changes treatment uses two steps, like the Wear OS watch:

1. **Prepare** (`.../prepare`): AAPS checks the request, computes the dose itself (BG, IOB,
   COB, limits) and parks it. It answers with an id and the confirmation lines it wrote.
2. **Commit** (`.../commit?id=`): after the user confirmed AAPS' lines on the watch, the
   parked action is done. Only the newest id is accepted, only once, and only for 60 s
   (bolus) or 120 s (temp target, scene).

The watch never sends an insulin amount except in "treatment", where the user picks it;
AAPS still rounds it to the pump step and limits it to max bolus.

Everything goes through the same AAPS code as the Wear watch (`BatchExecutor`,
`WizardExecutor`, `SceneActions`), with the same constraints, and is logged with source
"Garmin".

#### Security
The HTTP server only listens on `127.0.0.1`, but any app on the phone can call it.
Therefore:

- **Garmin watch control key** (new setting, at least 8 characters): needed for every
  command, also `/carbs` and `/connect`. It is never sent to a watch; you type the same
  key into the watch app. Setting it unlocks temp targets and scenes.
- **Allow carbs and bolus from Garmin watch** (off by default, can only be turned on when a
  control key is set): also needed for Quick Wizard, bolus wizard, treatment and carbs.
- **Request Key** (the existing AAPS key) opens no commands. AAPS sends it in its push
  messages to every watch app that has called `/get`, so it must not be trusted.
- Reading (`/get`, `/sgv.json`) needs no key.
- Wrong control key 5 times within 15 minutes (from any app; a right key in between does not
  reset the count): the watch commands are locked for 15 minutes.
- While locked, every command is refused (HTTP 429), even with the right key.
- Except stop bolus and `/status`: they are never locked. With the right key you can always
  stop a running bolus from the watch and see how far it has come.
- A bolus is refused while another bolus or SMB is queued or running, and at commit when a
  bolus or SMB was stored after AAPS began computing the prepared dose.
- Bolus commands are refused on AAPSClient.
- Requests with a line over 8 KB, more than 50 headers or a body over 16 KB are refused.

#### Endpoints
All need `key=<control key>`. Answers are JSON.

| Endpoint | What it does | Needs the switch |
|---|---|---|
| `/status` | `bolusRunning`, `ttActive`, `qwCount`, `sceneActive`, `sceneName`, `sceneCount`, `bolusEnabled`, `carbsEnabled`, `maxBolus`, `maxCarbs`, `bolusStep`, `wizardPercent`, `units`, and while a bolus runs `bolusPercent` and `bolusInsulin` (not after it was stopped) | no |
| `/tt/prepare?preset=activity\|eating\|hypo\|cancel` | temp target from the AAPS presets; cancel only when one is running | no |
| `/tt/prepare?preset=manual&target=X&duration=N` | manual temp target in the AAPS unit (mmol/L with a dot, or mg/dL), within the AAPS limits, 5-720 min | no |
| `/tt/commit?id=` | sets it | no |
| `/scene/list` | enabled scenes | no |
| `/scene/prepare?id=`, `/scene/commit?id=` | starts a scene | no |
| `/scene/stop` | ends the running scene, without starting a follow-up scene | no |
| `/qw/list` | Quick Wizard buttons for the watch, in their time window, not in their 30 min cooldown | yes |
| `/qw/prepare?guid=` | insulin and carbs buttons as they are (with eCarbs), wizard buttons computed by AAPS | yes |
| `/wizard/prepare?carbs=N&type=bread\|cake\|pizza&carbTime=M&percent=P` | bolus wizard with the Wear wizard settings; meal type splits the carbs like the AAPS bolus wizard; carb time -60..60 min (default 0), percentage 10..200 (default from AAPS) | yes |
| `/treatment/prepare?insulin=X.XX&carbs=N` | insulin and/or carbs as chosen | yes |
| `/bolus/commit?id=` | gives the prepared bolus, Quick Wizard or treatment | yes |
| `/bolus/discard?id=` | drops a prepared dose that will not be given (BACK or time ran out on the watch), so the loop gives SMBs again at once instead of after AAPS' pause. The watch ignores the answer (older builds answer 404) | no |
| `/bolus/stop` | stops a queued or running bolus | no |
| `/carbs` | unchanged from AAPS, but needs the control key | yes |
| `/connect` | unchanged from AAPS, but needs the control key | no |

Prepare answers: `{"ok":true,"id":"<id>","lines":[...],"insulin":<U>}` (`insulin` is left
out when unknown). Commit answers: `{"ok":true,"text":"OK"}` (for a bolus: queued in AAPS), a refusal
`{"ok":false,"error":"..."}`, or, when AAPS does not know whether it was done,
`{"ok":false,"error":"Unknown - do NOT repeat, check AAPS","unknown":true}`. The watch
must never repeat a command after an unknown answer, and the user must check AAPS. A second
commit of an id that was already sent also answers unknown, and so does a scene that may have
started only partly.

#### Not included
- Insulin from the watch without a confirmation, or a dose correction on the watch.
- eCarbs with their own amount and time (use the cake/pizza meal type or a Quick Wizard
  button with eCarbs).
- Profile switch, running mode, prime/fill.

### 5. Smaller fixes in the Garmin plugin
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
V1 watch faces and `/sgv.json` clients work as before. With no push watch face and no AAPS
key, AAPS does not connect to Garmin Connect, as before. `/carbs` and `/connect` now need the
control key (and `/carbs` the "Allow carbs and bolus from Garmin watch" switch), so an old
data field that sends carbs or a pump disconnect without it is refused.
Wear OS, the loop, pumps and all other plugins are plain AAPS 4.0.0.

## How to use it
1. Build the APK from branch `GarminPush+Steps+Control-V4.0.0` the same way as AAPS
   (see [Building the APK](https://wiki.aaps.app/en/latest/SettingUpAaps/BuildingAaps.html)).
2. In AAPS, enable **Garmin** under Configuration → Communication. Its settings are under
   Garmin → Settings.
3. Install a watch face that uses V2 and/or sends steps, for example the
   ["AAPS Push" watch face](https://github.com/Pwb0186/aaps-garmin-push-watchface).
   On the watch face, "Send steps to AAPS" must be on to send steps.
4. Only for commands: set a control key in the Garmin settings, turn on "Allow carbs and
   bolus from Garmin watch" if you want them, and install the
   [AAPS Control widget](https://github.com/Pwb0186/aaps-garmin-control-widget).

## Tested
- Tested by one user, in daily use with an Omnipod DASH and a Forerunner 955, on AAPS
  4.0.0-rc1 with the same Garmin code. On 4.0.0 so far with the host tests and a short test
  on the watch.
- Host tests:
```
./gradlew :plugins:sync:testAndroidHostTest --tests "app.aaps.plugins.sync.garmin.*"
```

## Files
All changes are in `plugins/sync/src/androidMain/kotlin/app/aaps/plugins/sync/garmin/`
and the tests next to it. New files: `GarminSteps.kt` (steps), `GarminV2Push.kt`
(registered push apps), `GarminQuery.kt` (query parser), `GarminBolus.kt`,
`GarminTempTarget.kt`, `GarminScene.kt` (commands), `GarminKeyGuard.kt` (control key lockout).

## Credits
- The Garmin plugin is by the AAPS developers.

---

# Original AAPS README

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
