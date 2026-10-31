# AAPS Garmin plugin: commands from the watch

This is experimental. Do not use it to dose insulin unless you understand and accept the
risks. Use at your own risk.

**Branch:** `GarminPush+Steps+Control-dev`, which is
[`GarminPush+Steps-dev`](https://github.com/Pwb0186/AndroidAPS/tree/GarminPush+Steps-dev)
(push-to-pull and steps on AAPS `dev`, see its README) plus the commands below.
**Same commands on AAPS 4.0.0-rc1:**
[`GarminPush+Steps+Control-V4.0.0-rc1`](https://github.com/Pwb0186/AndroidAPS/tree/GarminPush+Steps+Control-V4.0.0-rc1).
`dev` moves all the time; this branch is moved onto a newer `dev` now and then.

This branch adds commands from a Garmin watch app to the AAPS Garmin plugin: temp target,
scenes, Quick Wizard, bolus wizard, treatment (insulin and/or carbs) and stop bolus. The
matching watch app is a separate Connect IQ widget that you build and install yourself:
[AAPS Control widget](https://github.com/Pwb0186/aaps-garmin-control-widget).

## How a command works
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

## Security
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
- Wrong control key 5 times (in total, from any app): the watch commands are locked for
  15 minutes.
- While locked, every command is refused (HTTP 429), even with the right key.
- Except stop bolus: it is never locked. With the right key you can always stop a running
  bolus from the watch.
- A bolus is refused while another bolus or SMB is queued or running, and at commit when a
  bolus or SMB was stored after AAPS began computing the prepared dose.
- Bolus commands are refused on AAPSClient.
- Requests with a line over 8 KB, more than 50 headers or a body over 16 KB are refused.

## Endpoints
All need `key=<control key>`. Answers are JSON.

| Endpoint | What it does | Needs the switch |
|---|---|---|
| `/status` | `bolusRunning`, `ttActive`, `qwCount`, `sceneActive`, `sceneName`, `sceneCount`, `bolusEnabled`, `carbsEnabled`, `maxBolus`, `maxCarbs`, `bolusStep`, `wizardPercent`, `units` | no |
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
| `/bolus/stop` | stops a queued or running bolus | no |
| `/carbs`, `/connect` | unchanged from AAPS, but need the control key | no |

Prepare answers: `{"ok":true,"id":"<id>","lines":[...],"insulin":<U>}` (`insulin` is left
out when unknown). Commit answers: `{"ok":true,"text":"OK"}`, a refusal
`{"ok":false,"error":"..."}`, or, when AAPS does not know whether it was done,
`{"ok":false,"error":"Unknown - do NOT repeat, check AAPS","unknown":true}`. The watch
must never repeat a command after an unknown answer, and the user must check AAPS.

## Not included
- Insulin from the watch without a confirmation, or a dose correction on the watch.
- eCarbs with their own amount and time (use the cake/pizza meal type or a Quick Wizard
  button with eCarbs).
- Profile switch, running mode, prime/fill.

## Tests
Host tests in `plugins/sync/src/androidHostTest/.../garmin`: `GarminBolusTest`,
`GarminSceneTest`, `GarminTempTargetTest`, `GarminKeyGuardTest`, `GarminPluginTest`,
`HttpServerTest`.
```
./gradlew :plugins:sync:testAndroidHostTest --tests "app.aaps.plugins.sync.garmin.*"
```

The rc1 branch is tested by one user, in daily use with an Omnipod DASH and a Forerunner 955.
This `dev` branch is only tested with the host tests.
