# AAPS Garmin plugin: commands from the watch

This is experimental. Do not use it to dose insulin unless you understand and accept the
risks. Use at your own risk.

This branch is on AAPS `dev`. It adds commands from a Garmin watch app to the Garmin
plugin: temp target, scenes, Quick Wizard, bolus wizard, treatment (insulin and/or carbs)
and stop bolus. It has no push-to-pull and no steps.
The watch app is a separate Connect IQ widget that you build and install yourself:
[AAPS Control widget](https://github.com/Pwb0186/aaps-garmin-control-widget).

Other branches on this fork:
- [`GarminSteps-dev`](https://github.com/Pwb0186/AndroidAPS/tree/GarminSteps-dev): steps from the watch.
- [`GarminPush-dev`](https://github.com/Pwb0186/AndroidAPS/tree/GarminPush-dev): push-to-pull for watch faces.
- [`GarminPush+Steps+Control-dev`](https://github.com/Pwb0186/AndroidAPS/tree/GarminPush+Steps+Control-dev): all of them together.

## How a command works
A command that changes treatment has two steps, like on the Wear OS watch:

1. **Prepare** (`.../prepare`): AAPS checks the request and computes the dose itself (BG, IOB,
   COB, limits). It keeps the dose and answers with an id and the lines to show.
2. **Commit** (`.../commit?id=`): the user confirms AAPS' lines on the watch. Then AAPS does
   the action. Only the newest id works, only once, and only for 60 s (bolus) or 120 s
   (temp target, scene).

The watch sends an insulin amount only for "treatment", where the user picks it. AAPS
rounds it to the pump step and limits it to the max bolus. All commands use the same AAPS
code and limits as the Wear watch. They are logged with the source "Garmin".

## Security
The HTTP server listens only on `127.0.0.1`, but any app on the phone can call it.
Therefore:

- **Garmin remote control PIN** (new setting, 8 to 16 digits): needed for every command,
  also `/carbs` and `/connect`. AAPS never sends it to a watch. You type the same PIN into
  the watch app. With a PIN, temp targets and scenes work.
- **Allow carbs and bolus from Garmin watch** (off by default; it can only be turned on when
  a PIN is set): also needed for Quick Wizard, bolus wizard, treatment and carbs.
- The **Request Key** (the old AAPS key) opens no command. AAPS sends it to watch apps.
- Reading (`/get`, `/sgv.json`) is as before: it needs the Request Key only when one is set.
- 5 wrong PINs within 15 minutes (from any app) lock the commands for 15 minutes.
  A locked command gets HTTP 429, also with the right PIN.
  Stop bolus and `/status` are never locked: with the right PIN you can always stop a
  running bolus and see its progress.
- A bolus is refused while another bolus or SMB is queued or running. It is also refused
  at commit if a bolus or SMB was stored after AAPS began to compute the dose.
- Bolus commands are refused on AAPSClient.
- A request line over 8 KB, more than 50 headers or a body over 16 KB is refused.
- In the log of commands, the PIN is shown as `key=***`. Reading (`/get`, `/sgv.json`) logs as before.

## Endpoints
All need `key=<PIN>`. Answers are JSON.

| Endpoint | What it does | Needs the switch |
|---|---|---|
| `/status` | State for the watch: bolus running, temp target active, scenes, limits, units | no |
| `/tt/prepare?preset=activity\|eating\|hypo\|cancel` | temp target from an AAPS preset | no |
| `/tt/prepare?preset=manual&target=X&duration=N` | manual temp target in the AAPS unit, 5 to 720 min | no |
| `/tt/commit?id=` | sets the temp target | no |
| `/scene/list`, `/scene/prepare?id=`, `/scene/commit?id=`, `/scene/stop` | scenes | no |
| `/qw/list`, `/qw/prepare?guid=` | Quick Wizard buttons | yes |
| `/wizard/prepare?carbs=N&type=bread\|cake\|pizza&carbTime=M&percent=P&alarm=1` | bolus wizard | yes |
| `/treatment/prepare?insulin=X.XX&carbs=N` | insulin and/or carbs as chosen | yes |
| `/bolus/commit?id=` | gives the prepared dose | yes |
| `/bolus/discard?id=` | drops a prepared dose that will not be given | no |
| `/bolus/stop` | stops a queued or running bolus | no |
| `/carbs` | as before, but it needs the PIN | yes |
| `/connect` | as before, but it needs the PIN | no |

A commit answers `{"ok":true,...}`, a refusal `{"ok":false,"error":"..."}`, or, when AAPS
does not know if it was done, `"unknown":true`. The watch must never repeat a command after
"unknown". The user must check AAPS.

## Not included
- Insulin from the watch without a confirmation.
- Profile switch, running mode, prime/fill.

## Tests
```
./gradlew :plugins:sync:testAndroidHostTest --tests "app.aaps.plugins.sync.garmin.*"
```
This branch is only tested with the host tests.

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
