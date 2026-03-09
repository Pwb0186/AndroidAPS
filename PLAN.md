# Equil Wizard Compose Migration Plan

## Goal
Migrate Equil pump's XML fragment-based activation/deactivation/change-insulin wizards to Compose,
sharing infrastructure with Medtrum's existing Compose wizard. Preserve all logic, images, and user flows.

## Current State

### Equil (XML - to migrate)
- **3 Activities**: `EquilPairActivity`, `EquilUnPairDetachActivity`, `EquilUnPairActivity`
- **8 Fragments**: `EquilPairFragmentBase` + 7 subclasses
- **2 Dialogs**: `LoadingDlg`, `EquilAutoDressingDlg`
- **3 Workflows**: PAIR (6 steps), CHANGE_INSULIN (4 steps), UNPAIR (2 steps)
- **3 animated GIFs**: assemble, attach, detach (loaded via Glide)
- **Navigation**: NavGraph XML + Fragment-based

### Medtrum (Compose - reference)
- Single `MedtrumPatchScreen` with `AnimatedContent` step routing
- `MedtrumPatchViewModel` with two-tier state machine (PatchStep + SetupStep)
- Shared components in `core/ui/compose/pump/`: `WizardStepLayout`, `StepProgressIndicator`, `WizardButton`
- No images/GIFs in wizard steps

### Shared Components Available
- `core/ui/compose/pump/WizardStepLayout.kt` - scrollable content + pinned buttons
- `core/ui/compose/pump/StepProgressIndicator.kt` - circle-dot animated progress
- `core/ui/compose/pump/WizardButton` - immutable button descriptor

---

## Phase 0: Prerequisites & Shared Infrastructure

### 0.1 Add glide-compose dependency
- [x] Add `glide-compose = "1.0.0-beta01"` to `gradle/libs.versions.toml`
- [x] Add library entry
- [x] Add to `pump/equil/build.gradle.kts`
- [x] Add Hilt plugin + dependency to equil module (required for @HiltViewModel + @AndroidEntryPoint)
- [ ] Verify GIF loading works at runtime

### 0.2 Create shared WizardScreen composable
Extract the common Scaffold + TopAppBar + progress + AnimatedContent pattern from Medtrum.

**File:** `core/ui/src/main/kotlin/app/aaps/core/ui/compose/pump/WizardScreen.kt`

```kotlin
@Composable
fun <S> WizardScreen(
    currentStep: S,
    titleResId: Int,
    totalSteps: Int,
    currentStepIndex: Int,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onCancel: () -> Unit,          // cancel confirmation dialog
    cancelDialogTitle: String,
    cancelDialogText: String,
    stepContent: @Composable (step: S, onCancel: () -> Unit) -> Unit
)
```

This replaces the duplicated Scaffold/TopAppBar/AnimatedContent/BackHandler pattern.
Both Medtrum and Equil can use it (Medtrum refactoring optional, low priority).

### 0.3 Create WizardGifImage composable
**File:** `core/ui/src/main/kotlin/app/aaps/core/ui/compose/pump/WizardGifImage.kt`

```kotlin
@Composable
fun WizardGifImage(
    @DrawableRes gifResId: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier
)
```

Wraps `GlideImage` with consistent sizing (300dp) and styling for wizard instruction images.

**Files changed in Phase 0:** 4 new/modified
- `gradle/libs.versions.toml` (modify)
- `pump/equil/build.gradle.kts` (modify)
- `core/ui/compose/pump/WizardScreen.kt` (new)
- `core/ui/compose/pump/WizardGifImage.kt` (new)

**Compile check after Phase 0**

---

## Phase 1: EquilWizardViewModel

### 1.1 Define EquilWizardStep enum
**File:** `pump/equil/src/main/kotlin/app/aaps/pump/equil/ui/compose/EquilWizardStep.kt`

```kotlin
enum class EquilWizardStep {
    // PAIR flow
    ASSEMBLE,
    SERIAL_NUMBER,          // BLE scan + pairing
    FILL,                   // Manual/auto fill with motor control
    ATTACH,
    AIR,                    // Air removal + profile setup
    CONFIRM,                // Finalization + therapy events

    // CHANGE_INSULIN flow
    CHANGE_INSULIN,         // Detach + insulin change command
    // then reuses: ASSEMBLE, FILL, ATTACH (subset)

    // UNPAIR flow
    UNPAIR_DETACH,          // Detach animation + insulin change
    UNPAIR_CONFIRM,         // Confirm unpair + CmdUnPair

    // Terminal
    CANCEL,
    COMPLETE
}
```

### 1.2 Define workflow enum
```kotlin
enum class EquilWorkflow {
    PAIR,            // 6 steps: Assemble → Serial → Fill → Attach → Air → Confirm
    CHANGE_INSULIN,  // 4 steps: ChangeInsulin → Assemble → Fill → Attach
    UNPAIR           // 2 steps: UnpairDetach → UnpairConfirm
}
```

### 1.3 Create EquilWizardViewModel
**File:** `pump/equil/src/main/kotlin/app/aaps/pump/equil/ui/compose/EquilWizardViewModel.kt`

**Injected dependencies** (same as current fragments use):
- `equilManager`, `commandQueue`, `equilPumpPlugin`, `pumpSync`
- `equilHistoryRecordDao`, `preferences`, `constraintsChecker`
- `aapsLogger`, `rh`, `blePreCheck`

**State flows:**
- `wizardStep: StateFlow<EquilWizardStep?>` — current UI step
- `workflow: StateFlow<EquilWorkflow>` — active workflow
- `totalSteps: StateFlow<Int>` — step count for progress indicator
- `currentStepIndex: StateFlow<Int>` — current position
- `titleResId: StateFlow<Int>` — toolbar title resource
- `isLoading: StateFlow<Boolean>` — replaces LoadingDlg
- `errorMessage: StateFlow<String?>` — inline error display
- `scanProgress: StateFlow<Boolean>` — BLE scan active indicator

**Key methods (ported from fragment logic):**
- `initializeWorkflow(workflow, startStep?)` — set up flow
- `moveStep(step)` — navigate with validation
- `startBLEScan(serial, password)` — from SerialNumberFragment
- `startFill() / startAutoFill() / stopAutoFill()` — from FillFragment
- `startAirRemoval()` — from AirFragment
- `confirmActivation()` — from ConfirmFragment
- `startChangeInsulin()` — from ChangeInsulinFragment
- `startUnpairDetach() / confirmUnpair()` — from UnPair activities
- `handleCancel() / handleComplete()`

**Events:**
```kotlin
sealed class EquilWizardEvent {
    data object Finish : EquilWizardEvent()
    data object StartNewPairing : EquilWizardEvent()  // after unpair, user wants to re-pair
}
```

**Files changed in Phase 1:** 2 new
- `pump/equil/ui/compose/EquilWizardStep.kt` (new)
- `pump/equil/ui/compose/EquilWizardViewModel.kt` (new)

**Compile check after Phase 1**

---

## Phase 2: Simple Step Composables (static/GIF-based)

These steps are mostly static instruction screens with images and text.

### 2.1 AssembleStep
- GIF: `equil_animation_wizard_assemble`
- Conditional title: "Dressing" for CHANGE_INSULIN mode
- Uses `WizardGifImage` + `WizardStepLayout`

### 2.2 AttachStep
- GIF: `equil_animation_wizard_attach`
- Title + description text
- Simple Next button

### 2.3 ChangeInsulinStep
- GIF: `equil_animation_wizard_detach`
- Executes `CmdInsulinChange` via ViewModel
- Shows loading state, then advances

### 2.4 UnpairDetachStep
- GIF: `equil_animation_wizard_detach`
- Executes insulin change command
- Confirmation dialog before proceeding

### 2.5 UnpairConfirmStep
- Text only (device name display)
- Confirmation dialog with device name
- Executes `CmdUnPair`, clears data on success

**File:** `pump/equil/src/main/kotlin/app/aaps/pump/equil/ui/compose/steps/` (5 new files)

**Compile check after Phase 2**

---

## Phase 3: Complex Step Composables

### 3.1 SerialNumberStep (HIGH complexity)
Port from `EquilPairSerialNumberFragment` (380 lines).

**UI elements:**
- `OutlinedTextField` for serial number (6 hex digits)
- `OutlinedTextField` for password (4 hex digits)
- Pair button with loading/retry states
- Inline error messages (replaces toast)
- Scan progress indicator

**Logic (all in ViewModel):**
- Input validation (hex pattern matching)
- BLE scan start/stop with 15s timeout
- Command chain: `CmdDevicesOldGet` → `CmdPair` → `CmdSettingSet`
- Error states: scan timeout, unsupported firmware, wrong password

**State:**
- `serialInput`, `passwordInput` — text field state
- `scanState: Idle | Scanning | Found | Timeout | Error(message)`
- `pairState: Idle | Pairing | Success | Error(message)`

### 3.2 FillStep (HIGH complexity)
Port from `EquilPairFillFragment` (~150+ lines).

**UI elements:**
- Fill button → starts manual fill (motor step-by-step)
- Auto-fill button → shows inline progress with cancel (replaces `EquilAutoDressingDlg`)
- Finish button (appears after fill complete)
- Step count / resistance status

**Logic (all in ViewModel):**
- `CmdStepSet` → motor advancement
- `CmdResistanceGet` → piston detection (resistance >= 500)
- Auto-fill loop with cancel support
- Max step safety check

**State:**
- `fillState: Idle | Filling | FillingAuto | Resistance | Complete | Error`
- `motorSteps: Int`

### 3.3 AirStep (HIGH complexity)
Port from `EquilPairAirFragment` (~150+ lines).

**UI elements:**
- Air removal button
- Finish button
- Loading indicator during command chain

**Logic (all in ViewModel):**
- Air removal via `CmdStepSet`
- Command chain differs by workflow:
  - PAIR: `CmdAlarmSet` → `CmdBasalSet` → `CmdTimeSet` → `CmdDevicesGet`
  - CHANGE_INSULIN: `CmdAlarmSet` → `CmdBasalSet` → `CmdTimeSet`
- Profile validation and schedule conversion

### 3.4 ConfirmStep (MEDIUM complexity)
Port from `EquilPairConfirmFragment` (~124 lines).

**UI elements:**
- Confirmation text
- Finish button with loading state

**Logic (all in ViewModel):**
- Command chain: `CmdInsulinGet` → `CmdModelSet` → `CmdSettingSet`
- Database: insert `EquilHistoryRecord` entries (CANNULA_CHANGE, INSULIN_CHANGE)
- Therapy events via `pumpSync`
- Set `ActivationProgress.COMPLETED`
- `pumpSync.connectNewPump()` for PAIR workflow

**File:** `pump/equil/src/main/kotlin/app/aaps/pump/equil/ui/compose/steps/` (4 new files)

**Compile check after Phase 3**

---

## Phase 4: Wizard Screen & Entry Point

### 4.1 Create EquilWizardScreen
**File:** `pump/equil/src/main/kotlin/app/aaps/pump/equil/ui/compose/EquilWizardScreen.kt`

Uses shared `WizardScreen` composable. Maps `EquilWizardStep` → step composables:

```kotlin
@Composable
fun EquilWizardScreen(viewModel: EquilWizardViewModel) {
    WizardScreen(
        currentStep = wizardStep,
        ...
        stepContent = { step, onCancel ->
            when (step) {
                ASSEMBLE -> AssembleStep(viewModel, onCancel)
                SERIAL_NUMBER -> SerialNumberStep(viewModel, onCancel)
                FILL -> FillStep(viewModel, onCancel)
                // ...
            }
        }
    )
}
```

### 4.2 Wire into EquilFragment / EquilPumpPlugin
- Replace `startActivity(Intent(EquilPairActivity))` calls with Compose state toggle
- Show `EquilWizardScreen` overlay when wizard is active
- Handle protection check before showing wizard (for CHANGE_INSULIN)

**Decision point:** Equil may not yet have a full ComposeContent pattern like Medtrum.
If EquilFragment is still XML-based, the wizard can be launched as a Compose Activity
(single activity wrapping `EquilWizardScreen`) or integrated inline if Equil overview is
already Compose. Check current state before implementing.

**Compile check after Phase 4**

---

## Phase 5: Cleanup

### 5.1 Remove old XML files
Only after all workflows verified working:

- [ ] Delete `EquilPairActivity.kt`
- [ ] Delete `EquilPairFragmentBase.kt`
- [ ] Delete all 7 fragment subclasses (`EquilPairAssembleFragment`, etc.)
- [ ] Delete `EquilUnPairDetachActivity.kt`
- [ ] Delete `EquilUnPairActivity.kt`
- [ ] Delete `LoadingDlg.kt`
- [ ] Delete `EquilAutoDressingDlg.kt`
- [ ] Delete navigation graph: `equil_pair_navigation_graph.xml`
- [ ] Delete 15 XML layouts (pair fragments, dialogs, nav buttons, progress)
- [ ] Remove old Glide `api()` dependency from `build.gradle.kts` (keep only glide-compose)
- [ ] Remove unused string resources (if any became orphaned)
- [ ] Remove AndroidManifest activity declarations
- [ ] Clean up unused styles (`pairTitle`, `pairText`, `GrayButton`, etc.)

### 5.2 Verify
- [ ] All 3 workflows function correctly (PAIR, CHANGE_INSULIN, UNPAIR)
- [ ] GIF animations display and animate
- [ ] BLE scanning works
- [ ] Motor control (fill) works
- [ ] Command chains complete successfully
- [ ] Error states and retry work
- [ ] Back navigation and cancel work
- [ ] Progress indicator shows correct step counts
- [ ] Screen stays on during wizard
- [ ] No leaked references or memory issues

**Compile check after Phase 5**

---

## File Count Summary

| Phase | New Files | Modified Files | Deleted Files |
|-------|-----------|----------------|---------------|
| 0     | 2         | 2              | 0             |
| 1     | 2         | 0              | 0             |
| 2     | 5         | 0              | 0             |
| 3     | 4         | 0              | 0             |
| 4     | 1-2       | 1-2            | 0             |
| 5     | 0         | 1-2            | ~25           |
| **Total** | **14-15** | **4-6** | **~25** |

## Complexity & Risk Assessment

| Area | Risk | Mitigation |
|------|------|------------|
| BLE scanning in Compose | Medium | Keep scan logic in ViewModel, only UI changes |
| Motor control commands | Medium | Commands unchanged, just callback → StateFlow |
| GIF loading (glide-compose beta) | Low | Well-tested library, simple use case |
| Command chain ordering | High | Preserve exact sleep/sequence from fragments |
| Crypto state continuity | Low | EquilBLE layer unchanged |
| Protection check wiring | Low | Follow Medtrum pattern |

## Key Decisions Made
1. **glide-compose** for GIF loading (leverages existing Glide)
2. **Inline loading** (Material 3) replaces modal `LoadingDlg`
3. **Single Compose screen** with workflow routing (no separate Activities)
4. **Circle-dot StepProgressIndicator** (shared with Medtrum)
5. **Shared WizardScreen** extracted for both pumps
6. **BLE pairing** stays Equil-specific (not shared component)
7. **stripHtml()** in Medtrum stays as-is
