# Equil Pump Compose Migration Review (2026-03-09)

## DI Pattern
- Equil uses Hilt (`@HiltViewModel` + `hiltViewModel()`) matching Medtrum reference. This is the
  CORRECT pattern for this module — NOT the Dagger ViewModelFactory pattern used by NSClient/Tidepool/Wear.
- `build.gradle.kts` correctly adds `libs.plugins.hilt`, `com.google.dagger.hilt.android`,
  `androidx.hilt.navigation.compose`, and `ksp(libs.com.google.dagger.hilt.compiler)`.
- `EquilComposeContent` is constructed manually in `EquilPumpPlugin` (not DI) — same pattern as
  Medtrum. `protectionCheck` and `blePreCheck` are constructor params on the plugin for this purpose.

## Critical Bugs Found
- Duplicate `CANNULA_CHANGE` therapy event in `saveActivation()` — both the `if (PAIR)` block and
  the unconditional block below call `insertTherapyEventIfNewWithTimestamp(CANNULA_CHANGE)`.
- `CHANGE_INSULIN` workflow `totalSteps = 4` but actual flow runs 6 steps (ChangeInsulin →
  Assemble → Fill → Attach → Air → Confirm). Progress indicator goes backwards on Air/Confirm steps
  because `updateStepProgress` has no mapping for them.
- `EquilWizardEvent.ShowMessage` is emitted in `startUnpairDetach()` on failure but the collector
  in `EquilComposeContent` has an empty `when` branch — error silently swallowed.
- `BlePreCheckHost` and `EquilWizardScreen` render simultaneously in `EquilComposeContent`;
  wizard renders before BLE check completes (same bug as EOPatch2).
- `AirStep` has no guard preventing the "Finish" button from being tapped before air removal
  command is sent — skips the air removal step entirely.
- `canGoBack` is a plain Kotlin `val` getter reading `_wizardStep.value` — not reactive in Compose.

## State / Navigation
- System Back button in `EquilWizardScreen` calls `handleCancel()` directly (no confirmation
  dialog); Cancel button in steps shows dialog first — inconsistent behavior vs. original.
- `_titleResId` initialized to `R.string.equil_common_wizard_button_next` (wrong string) — visible
  briefly before `initializeWorkflow` runs.

## Shared Infrastructure
- `WizardGifImage.kt` in `core/ui/compose/pump/` exports `WizardImage` (not `WizardGifImage`).
  No step composable uses it — all call `GlideImage` directly with copy-pasted boilerplate.
  File is dead code as shipped.
- `glide-compose = "1.0.0-beta01"` added to `libs.versions.toml`. The old `com.github.bumptech.glide`
  entry remains (used by other modules). Both coexist correctly.

## Style Violations
- `rh: ResourceHelper` is `public val` on `EquilWizardViewModel` — should be `private val`.
- `SerialNumberStep` has two `OutlinedTextField` but no `clearFocusOnTap` modifier.
- Hardcoded `8.dp`, `12.dp`, `300.dp`, `128.dp`, `24.dp`, `20.dp`, `2.dp` throughout step
  composables — should use `AapsSpacing` constants or named domain constants.
- No `@Preview` composables in any new Compose files.
- `EquilWizardScreen` and `EquilOverviewScreen` should be `internal`; step composables `private`.
- `UnpairConfirmStep` reads serial number via `viewModel.getSerialNumber()` (non-reactive plain
  function call) — should be a `StateFlow<String>`.
- `EquilOverviewViewModel` custom scope uses `Dispatchers.Default` but calls `rh.gs()` which
  accesses Android Context — prefer `Dispatchers.Main`.
- Both UNPAIR steps use `R.string.equil_change` as title — no step distinction for user.
