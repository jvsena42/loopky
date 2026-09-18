import SwiftUI
import Shared

/// Verify by SMS. **One screen for both phases**, not two routes — splitting them would lose the
/// typed number when someone steps back to correct it.
struct PhoneVerificationScreen: View {
    var onBack: () -> Void
    var onDone: () -> Void

    @State private var viewModel: PhoneVerificationViewModel?
    @State private var uiState: PhoneVerificationUiState?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?
    @State private var phoneNumber = ""
    @State private var code = ""
    @State private var isPickingCountry = false

    private var isCodeEntry: Bool {
        uiState?.phase == PhoneVerificationPhase.codeentry
    }

    var body: some View {
        SignupScaffold(
            title: isCodeEntry ? "signup_code_title" : "signup_phone_title",
            subtitle: subtitle,
            errorTitle: SignupErrorCopy.title(for: uiState?.error),
            errorMessage: SignupErrorCopy.message(for: uiState?.error),
            // Back out of the code step returns to the number, keeping it — the same intent as
            // Android intercepting the system back gesture here.
            onBack: { isCodeEntry ? viewModel?.onBackToNumber() : onBack() }
        ) {
            if isCodeEntry { codeEntry } else { numberEntry }
        }
        .sheet(isPresented: $isPickingCountry) {
            if let uiState {
                CountryPickerSheet(selected: uiState.country) { viewModel?.onCountrySelected(country: $0) }
            }
        }
        .onAppear { attach() }
        .onDisappear { detach() }
    }

    private var subtitle: String {
        isCodeEntry
            ? String(format: NSLocalizedString("signup_code_subtitle", comment: ""),
                     uiState?.displayNumber ?? "")
            : NSLocalizedString("signup_phone_subtitle", comment: "")
    }

    private var numberEntry: some View {
        VStack(alignment: .leading, spacing: 0) {
            FieldLabel(text: "signup_phone_label")
            Spacer().frame(height: 8)
            HStack(spacing: 8) {
                CountryCodeButton(
                    country: uiState?.country ?? DialingCountries.shared.fallback,
                    isEnabled: !(uiState?.isWorking ?? false),
                    action: { isPickingCountry = true }
                )
                .accessibilityIdentifier("signup_phone_country")

                SignupTextField(
                    text: $phoneNumber,
                    placeholder: nil,
                    isEnabled: !(uiState?.isWorking ?? false),
                    isError: uiState?.error != nil,
                    keyboard: .phonePad
                )
                .textContentType(.telephoneNumber)
                .onChange(of: phoneNumber) { _, value in
                    viewModel?.onPhoneNumberChange(value: value)
                }
                .accessibilityIdentifier("signup_phone_input")
            }

            Spacer().frame(height: 8)
            // Once the number is complete, read it back in full: the trunk `0` it may have dropped
            // and the calling code it added are both invisible in the field.
            Text(verbatim: phoneHint)
                .font(.system(size: 12))
                .foregroundStyle(LoopkyColor.foregroundMuted)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("signup_phone_hint")

            // Withheld entirely on a terminal rate limit, not disabled: a button that cannot ever
            // work is worse than no button, because it invites tapping.
            if !(uiState?.isTerminal ?? false) {
                Spacer().frame(height: 24)
                SignupPrimaryButton(
                    title: "signup_phone_send",
                    isLoading: uiState?.isWorking ?? false,
                    isEnabled: uiState?.canSendCode ?? false,
                    action: { viewModel?.onSendCodeClick() }
                )
                .accessibilityIdentifier("signup_phone_send")
            }
        }
    }

    private var codeEntry: some View {
        VStack(alignment: .leading, spacing: 0) {
            FieldLabel(text: "signup_code_label")
            Spacer().frame(height: 8)
            SignupTextField(
                text: $code,
                placeholder: nil,
                isEnabled: !(uiState?.isWorking ?? false),
                isError: uiState?.error != nil,
                keyboard: .numberPad
            )
            .onChange(of: code) { _, value in viewModel?.onCodeChange(value: value) }
            .accessibilityIdentifier("signup_code_input")

            if !(uiState?.isTerminal ?? false) {
                Spacer().frame(height: 24)
                SignupPrimaryButton(
                    title: "signup_code_verify",
                    isLoading: uiState?.isWorking ?? false,
                    isEnabled: uiState?.canVerify ?? false,
                    action: { viewModel?.onVerifyClick() }
                )
                .accessibilityIdentifier("signup_code_verify")

                Spacer().frame(height: 10)
                Button(action: { viewModel?.onSendCodeClick() }) {
                    Text(verbatim: resendLabel)
                }
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(canResend ? LoopkyColor.accentSecondary : LoopkyColor.foregroundMuted)
                .disabled(!canResend)
                .accessibilityIdentifier("signup_code_resend")
            }
        }
    }

    private var canResend: Bool { uiState?.canResend ?? false }

    private var phoneHint: String {
        guard let uiState, uiState.canSendCode else {
            return NSLocalizedString("signup_phone_hint", comment: "")
        }
        return String(format: NSLocalizedString("signup_phone_sending_to", comment: ""), uiState.displayNumber)
    }

    private var resendLabel: String {
        canResend
            ? NSLocalizedString("signup_code_resend", comment: "")
            : String(format: NSLocalizedString("signup_code_resend_in", comment: ""),
                     Int(uiState?.resendCooldownSeconds ?? 0))
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.phoneVerificationViewModel()
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { value in
            guard let state = value as? PhoneVerificationUiState else { return }
            // The VM can restore a number on launch when a code was sent but never entered, so the
            // field follows state rather than only feeding it.
            // A pasted `+44 …` moves the picker and leaves only the national part, so the field
            // takes that back too. Only then: the field otherwise owns its own text while typing.
            if state.phoneNumber != phoneNumber && (phoneNumber.isEmpty || phoneNumber.contains("+")) {
                phoneNumber = state.phoneNumber
            }
            uiState = state
        }
        effectSink = FlowEffectSink(vm.effects) { effect in
            if effect is PhoneVerificationEffectNavigateToHandoff { onDone() }
        }
    }

    private func detach() {
        viewModel?.release()
        viewModel = nil
        stateSink = nil
        effectSink = nil
    }
}
