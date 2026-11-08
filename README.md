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

Without a remote control PIN, every command is refused (see [Security](#security)). Then the branch
only adds push, steps and the extra `/get` fields.

## What it adds

### 1. Push-to-pull for watch faces (V2)
Today a Garmin watch face asks AAPS for data on a timer, so the data can be up to
5 minutes old. Or AAPS sends all the data over Connect IQ (V1).
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
- AAPS sends a push on a new glucose value. It also sends one after a bolus, carbs, temp
  target, temp basal, extended bolus or running mode change. It waits 3.5 s to group
  changes, and sends at most one push per 3 s.
- The push message is only `{key, command: "updateWatch"}`.
- AAPS only connects to Garmin Connect when an AAPS key is set (as before) or a push
  watch face has fetched data in the last 15 minutes.

`/get` also returns more fields: `carbsOnBoard`, `temporaryTarget*`, `loopEnabled` and
`timestamp`. `carbsOnBoard` is left out when AAPS has no COB yet. Then the watch keeps its
last value and does not show 0.

### 2. Steps from the watch
A watch face can send today's step total on `/get` (`steps=<total>`). It reads the total
from `ActivityMonitor`. AAPS stores the steps in the same way as the Wear OS app. So the steps:

- show in the steps graph in AAPS,
- can be used by the Automation trigger **"Steps count"** (5, 10, 15, 30, 60 and
  180 minutes).

For each reading AAPS stores one record per window (5, 10, 15, 30, 60, 180 min) with
the steps in that window. Rules for the total:

- The first value is only a starting point. Nothing is stored.
- Sometimes AAPS cannot be reached, for example when the phone is out of range, Bluetooth
  is off or AAPS is stopped. Then the watch face keeps its totals for up to 3 hours. It
  sends them with the next request (`stepsPrev`). AAPS reads each total at its own time.
  So there is no gap: all windows are right at once, and the steps graph is filled in.
- Some watch faces do not keep totals, and a gap can be longer than 3 hours. If more than
  12 minutes passed since the last value and there are no kept totals, only the starting
  point moves. We do not know when those steps were taken.
- At local midnight the watch resets its counter. This is handled.
- A lower value on the same day only moves the starting point. This happens, for example,
  with a new watch or a second watch that sends to the same AAPS.
- The watch face sends the watch model in `device=`. A watch of another model starts again
  from a new starting point. So if you switch between a daily watch and a sport watch, the
  difference is never counted as steps.
- Two watches that send at the same time store no steps. Only one watch should send steps
  to an AAPS. Turn off "Send steps to AAPS" on the other one. Two watches of the same model
  cannot be told apart.
- A delta of 0 steps is stored too, so a rule like "fewer than 100 steps" works.
- The steps of a reading are spread evenly over the time since the last reading.
- A window is only stored when the history covers all of it. After the first reading or a
  long gap, the 5 minute window comes after 5 minutes and the 180 minute
  window after 3 hours. Until then the trigger finds no record for that window, never
  a count that is too low.
- The history is saved, so the windows go on after an AAPS restart. If the restart took
  more than 12 minutes, this only works with the totals the watch face kept.

Limit: the watch sends steps about every 5 minutes. The "Steps count" trigger only looks
at records from the last 5 minutes. So when the watch answers late or a glucose value is
missing, the trigger finds no record for a short time.

### 3. Pump status
`/get` also returns, when AAPS knows it:

- `reservoir`: insulin left in whole U (U100). Left out when unknown, on AAPSClient, and for
  a patch pump that only reports "50+" (Omnipod).
- `reservoirLevel`: 0 normal, 1 warning, 2 critical, with the AAPS status light thresholds.
- `cannulaChangeSec`: time of the last cannula or pod change, in seconds.
- `cannulaWarnH` and `cannulaCritH`: hours from the AAPS status light settings. The watch
  counts the age itself.

### 4. Commands from the watch
Temp target, scenes, Quick Wizard, bolus wizard, treatment (insulin and/or carbs) and stop
bolus.

#### How a command works
Every command that changes treatment uses two steps, like the Wear OS watch:

1. **Prepare** (`.../prepare`): AAPS checks the request. It computes the dose itself from
   BG, IOB, COB and its limits, and keeps it waiting. It answers with an id and its own
   confirmation lines.
2. **Commit** (`.../commit?id=`): the user has confirmed AAPS' lines on the watch. Now AAPS
   does the waiting action. It accepts only the newest id, only once, and only for 60 s
   (bolus) or 120 s (temp target, scene).

The watch only sends an insulin amount in "treatment", where the user picks it. AAPS still
rounds it to the pump step and limits it to max bolus.

Everything goes through the same AAPS code as the Wear watch: `BatchExecutor`,
`WizardExecutor` and `SceneActions`. The same limits apply. AAPS logs it with the source
"Garmin".

#### Security
The HTTP server only listens on `127.0.0.1`, but any app on the phone can call it.
Therefore:

- **Garmin remote control PIN** (new setting, 8 to 16 digits): every command needs
  it, also `/carbs` and `/connect`. AAPS never sends it to a watch. You type the same PIN
  into the watch app, under **Remote PIN**. The PIN alone unlocks temp targets and scenes.
- **Allow carbs and bolus from Garmin watch**: Quick Wizard, bolus wizard, treatment and
  carbs also need this switch. It is off by default. You can only turn it on when a remote
  control PIN is set.
- **Request Key** (the existing AAPS key) opens no commands. AAPS sends it in its push
  messages to every watch app that has called `/get`, so it must not be trusted.
- Reading (`/get`, `/sgv.json`) needs no key.
- After 5 wrong PINs within 15 minutes, the watch commands are locked for
  15 minutes. The wrong PINs can come from any app. A right PIN in between does not reset
  the count.
- While locked, every command is refused (HTTP 429), even with the right PIN.
- Except stop bolus and `/status`: they are never locked. With the right PIN you can always
  stop a running bolus from the watch and see how far it has come.
- A bolus is refused while another bolus or SMB is queued or running.
- A commit is refused when a bolus or SMB was stored after AAPS started to compute the
  waiting dose.
- Bolus commands are refused on AAPSClient.
- Requests with a line over 8 KB, more than 50 headers or a body over 16 KB are refused.

#### Endpoints
All need `key=<remote control PIN>`. Answers are JSON.

| Endpoint | What it does | Needs the switch |
|---|---|---|
| `/status` | `bolusRunning`, `ttActive`, `qwCount`, `sceneActive`, `sceneName`, `sceneCount`, `bolusEnabled`, `carbsEnabled`, `maxBolus`, `maxCarbs`, `bolusStep`, `wizardPercent`, `units`, and while a bolus runs `bolusPercent`, `bolusInsulin` and, when AAPS knows it, `bolusDelivered` (the units really given, in U with 2 decimals; not after it was stopped) | no |
| `/tt/prepare?preset=activity\|eating\|hypo\|cancel` | temp target from the AAPS presets; cancel only when one is running | no |
| `/tt/prepare?preset=manual&target=X&duration=N` | manual temp target in the AAPS unit (mmol/L with a dot, or mg/dL), within the AAPS limits, 5-720 min | no |
| `/tt/commit?id=` | sets it | no |
| `/scene/list` | enabled scenes | no |
| `/scene/prepare?id=`, `/scene/commit?id=` | starts a scene | no |
| `/scene/stop` | ends the running scene, without starting a follow-up scene | no |
| `/qw/list` | Quick Wizard buttons for the watch, in their time window, not in their 30 min cooldown | yes |
| `/qw/prepare?guid=` | insulin and carbs buttons as they are (with eCarbs), wizard buttons computed by AAPS | yes |
| `/wizard/prepare?carbs=N&type=bread\|cake\|pizza&carbTime=M&percent=P&alarm=1` | bolus wizard with the Wear wizard settings; meal type splits the carbs like the AAPS bolus wizard; carb time -60..60 min (default 0), percentage 10..200 (default from AAPS). alarm=1 asks AAPS for a reminder to eat when carbTime is above 0. | yes |
| `/treatment/prepare?insulin=X.XX&carbs=N` | insulin and/or carbs as chosen | yes |
| `/bolus/commit?id=` | gives the prepared bolus, Quick Wizard or treatment | yes |
| `/bolus/discard?id=` | drops a waiting dose that will not be given (BACK, or the time ran out on the watch). Then the loop can give SMBs again at once. The watch ignores the answer; older builds answer 404 | no |
| `/bolus/stop` | stops a queued or running bolus | no |
| `/carbs` | unchanged from AAPS, but needs the remote control PIN | yes |
| `/connect` | unchanged from AAPS, but needs the remote control PIN | no |

Prepare answers: `{"ok":true,"id":"<id>","lines":[...],"insulin":<U>}`. `insulin` is left
out when it is unknown.

Commit answers one of these:
- `{"ok":true,"text":"OK"}`. For a bolus this means it is in the AAPS queue.
- `{"ok":false,"error":"..."}`: refused, nothing was done.
- `{"ok":false,"error":"Unknown - do NOT repeat, check AAPS","unknown":true}`: AAPS does not
  know if it was done. The watch must never repeat the command, and the user must check AAPS.
  A second commit of an id that was already sent also gets this answer. So does a scene that
  may have started only in part.

#### Not included
- Insulin from the watch without a confirmation, or a dose correction on the watch.
- eCarbs with their own amount and time. Use the cake or pizza meal type, or a Quick Wizard
  button with eCarbs.
- Profile switch, running mode, prime/fill.

### 5. Exercise log from the watch
A data field can log a workout in AAPS. AAPS stores it as a Careportal **Exercise** event.
You see it in the graph and in the treatments list, and it goes to Nightscout. It does not
change any dosing.

`GET /exercise?start=<epoch seconds>&duration=<minutes>&note=<text>`

- It needs no key and no PIN, like `/get`. It only writes a note, no treatment.
- `start`: at most 2 minutes in the future and at most 24 hours old.
- `duration`: 1 to 1080 minutes (18 hours, a full Ironman).
- `note`: optional. AAPS trims it and cuts it to 60 characters.
- If an Exercise event with the same start time exists, AAPS writes nothing and answers
  `{"ok":true,"text":"Already logged"}`. So the watch can send the same workout again.
- A new event gives `{"ok":true,"text":"OK"}`. A refusal gives `{"ok":false,"error":"..."}`.
- On AAPSClient it is refused and AAPS shows a notification, as for `/carbs`. The event
  would stay on the client and never reach the master.
- AAPS logs it in the user log with the source "Garmin".

### 6. Smaller fixes in the Garmin plugin
- If the watch does not answer a message within 20 s, AAPS sends it once more. If a newer
  message waits, the old one is dropped. Before, one lost message blocked the queue for
  that app. Old V1 messages are not sent again.
- Some calls to Garmin Connect can fail, for example while it updates. AAPS now catches
  and logs these errors and does not crash.
- Query values are decoded once. Before, an AAPS key with `+` or `%` never matched.
- The AAPS key is masked in the log (`key=***`).
- `/get` without heart rate no longer logs "average heart rate 0 BPM 1970-01-01".
- Heart rate is stored with the end of the measuring period as its time. This is what
  AAPS expects. Before, it was stored 5 minutes too early.

## What is not changed
V1 watch faces and `/sgv.json` clients work as before. With no push watch face and no AAPS
key, AAPS does not connect to Garmin Connect, as before.

One change: `/carbs` and `/connect` now need the remote control PIN. `/carbs` also needs the
"Allow carbs and bolus from Garmin watch" switch. So an old data field that sends carbs or
a pump disconnect without them is refused.
Wear OS, the loop, pumps and all other plugins are plain AAPS 4.0.0.

## How to use it
1. Build the APK from branch `GarminPush+Steps+Control-V4.0.0` the same way as AAPS
   (see [Building the APK](https://wiki.aaps.app/en/latest/SettingUpAaps/BuildingAaps.html)).
2. In AAPS, enable **Garmin** under Configuration → Communication. Its settings are under
   Garmin → Settings.
3. Install a watch face that uses V2 or sends steps, for example the
   ["AAPS Push" watch face](https://github.com/Pwb0186/aaps-garmin-push-watchface).
   To send steps, turn on "Send steps to AAPS" on the watch face.
4. Only for commands: set a remote control PIN in the Garmin settings. If you want carbs and
   bolus, turn on "Allow carbs and bolus from Garmin watch". Then install the
   [AAPS Control widget](https://github.com/Pwb0186/aaps-garmin-control-widget).

## Tested
- One user uses it every day with an Omnipod DASH and a Forerunner 955. This was on AAPS
  4.0.0-rc1 with the same Garmin code. On 4.0.0 it is so far tested with the host tests and
  a short test on the watch.
- Host tests:
```
./gradlew :plugins:sync:testAndroidHostTest --tests "app.aaps.plugins.sync.garmin.*"
```

## Files
All changes are in `plugins/sync/src/androidMain/kotlin/app/aaps/plugins/sync/garmin/`
and the tests next to it. New files: `GarminSteps.kt` (steps), `GarminV2Push.kt`
(registered push apps), `GarminQuery.kt` (query parser), `GarminBolus.kt`,
`GarminTempTarget.kt`, `GarminScene.kt` (commands), `GarminKeyGuard.kt` (PIN lockout),
`GarminExercise.kt` (exercise log).

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
