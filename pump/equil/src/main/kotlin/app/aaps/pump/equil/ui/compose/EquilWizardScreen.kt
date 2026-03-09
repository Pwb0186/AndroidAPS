package app.aaps.pump.equil.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.ui.compose.pump.WizardScreen
import app.aaps.pump.equil.R
import app.aaps.pump.equil.ui.compose.steps.AirStep
import app.aaps.pump.equil.ui.compose.steps.AssembleStep
import app.aaps.pump.equil.ui.compose.steps.AttachStep
import app.aaps.pump.equil.ui.compose.steps.ChangeInsulinStep
import app.aaps.pump.equil.ui.compose.steps.ConfirmStep
import app.aaps.pump.equil.ui.compose.steps.FillStep
import app.aaps.pump.equil.ui.compose.steps.SerialNumberStep
import app.aaps.pump.equil.ui.compose.steps.UnpairConfirmStep
import app.aaps.pump.equil.ui.compose.steps.UnpairDetachStep

@Composable
internal fun EquilWizardScreen(viewModel: EquilWizardViewModel) {
    val wizardStep by viewModel.wizardStep.collectAsStateWithLifecycle()
    val titleResId by viewModel.titleResId.collectAsStateWithLifecycle()
    val totalSteps by viewModel.totalSteps.collectAsStateWithLifecycle()
    val currentStepIndex by viewModel.currentStepIndex.collectAsStateWithLifecycle()
    val canGoBack by viewModel.canGoBack.collectAsStateWithLifecycle()

    WizardScreen(
        currentStep = wizardStep,
        title = stringResource(titleResId),
        totalSteps = totalSteps,
        currentStepIndex = currentStepIndex,
        canGoBack = canGoBack,
        onBack = { viewModel.handleCancel() },
        cancelDialogTitle = stringResource(R.string.equil_common_wizard_exit_confirmation_title),
        cancelDialogText = stringResource(R.string.equil_common_wizard_exit_confirmation_text),
        stepContent = { step, onCancel ->
            when (step) {
                EquilWizardStep.ASSEMBLE       -> AssembleStep(viewModel, onCancel)
                EquilWizardStep.SERIAL_NUMBER   -> SerialNumberStep(viewModel, onCancel)
                EquilWizardStep.FILL            -> FillStep(viewModel, onCancel)
                EquilWizardStep.ATTACH          -> AttachStep(viewModel, onCancel)
                EquilWizardStep.AIR             -> AirStep(viewModel, onCancel)
                EquilWizardStep.CONFIRM         -> ConfirmStep(viewModel, onCancel)
                EquilWizardStep.CHANGE_INSULIN  -> ChangeInsulinStep(viewModel, onCancel)
                EquilWizardStep.UNPAIR_DETACH   -> UnpairDetachStep(viewModel, onCancel)
                EquilWizardStep.UNPAIR_CONFIRM  -> UnpairConfirmStep(viewModel, onCancel)
                EquilWizardStep.CANCEL          -> {} // Terminal, finish event emitted
            }
        }
    )
}
