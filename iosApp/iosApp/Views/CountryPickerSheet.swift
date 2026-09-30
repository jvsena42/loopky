import SwiftUI
import Shared

/// The flag and calling code in front of the phone number; opens `CountryPickerSheet`.
struct CountryCodeButton: View {
    let country: DialingCountry
    var isEnabled: Bool = true
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Text(verbatim: country.flag)
                    .font(.system(size: 20))
                Text(verbatim: country.prefix)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(LoopkyColor.foregroundPrimary)
                Image(systemName: "chevron.down")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(LoopkyColor.foregroundMuted)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 14)
            .background(RoundedRectangle(cornerRadius: 12).fill(LoopkyColor.surfaceCard))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(LoopkyColor.borderSubtle, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .disabled(!isEnabled)
        .accessibilityLabel(Text(verbatim: String(
            format: NSLocalizedString("signup_phone_country_button", comment: ""),
            CountryNames.name(of: country),
            country.prefix
        )))
    }
}

/// Every country, sorted by its name in the app's language, with the system search field.
/// A sheet rather than a `Menu`/`Picker`: two hundred-odd rows are only usable with search.
struct CountryPickerSheet: View {
    let selected: DialingCountry
    var onSelect: (DialingCountry) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    private let entries: [CountryEntry] = DialingCountries.shared.all
        .map { CountryEntry(country: $0, name: CountryNames.name(of: $0)) }
        .sorted { $0.name.compare($1.name, locale: Locale.current) == .orderedAscending }

    private var visible: [CountryEntry] {
        let needle = query.trimmingCharacters(in: .whitespaces)
        guard !needle.isEmpty else { return entries }
        let digits = needle.hasPrefix("+") ? String(needle.dropFirst()) : needle
        if !digits.isEmpty, digits.allSatisfy(\.isNumber) {
            return entries.filter { $0.country.dialCode.hasPrefix(digits) }
        }
        return entries.filter {
            $0.name.localizedStandardContains(needle)
                || $0.country.regionCode.caseInsensitiveCompare(needle) == .orderedSame
        }
    }

    var body: some View {
        NavigationStack {
            List(visible) { entry in
                row(entry)
            }
            .listStyle(.plain)
            .overlay {
                if visible.isEmpty {
                    ContentUnavailableView {
                        Label("signup_phone_country_empty", systemImage: "magnifyingglass")
                    }
                }
            }
            .searchable(
                text: $query,
                placement: .navigationBarDrawer(displayMode: .always),
                prompt: Text("signup_phone_country_search")
            )
            .autocorrectionDisabled()
            .navigationTitle(Text("signup_phone_country"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(role: .cancel) { dismiss() } label: {
                        Image(systemName: "xmark")
                    }
                }
            }
        }
        .presentationDetents([.large])
    }

    private func row(_ entry: CountryEntry) -> some View {
        let isSelected = entry.country == selected
        return Button {
            onSelect(entry.country)
            dismiss()
        } label: {
            HStack(spacing: 12) {
                Text(verbatim: entry.country.flag)
                    .font(.system(size: 22))
                Text(verbatim: entry.name)
                    .foregroundStyle(LoopkyColor.foregroundPrimary)
                Spacer()
                Text(verbatim: entry.country.prefix)
                    .foregroundStyle(LoopkyColor.foregroundMuted)
                if isSelected {
                    Image(systemName: "checkmark")
                        .foregroundStyle(LoopkyColor.accentPrimary)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: "\(entry.name), \(entry.country.prefix)"))
        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
        .accessibilityIdentifier("signup_country_\(entry.country.regionCode)")
    }
}

private struct CountryEntry: Identifiable {
    let country: DialingCountry
    let name: String

    var id: String { country.regionCode }
}

/// Names come from the system's locale data, which already speaks every language Loopky ships.
enum CountryNames {
    static func name(of country: DialingCountry) -> String {
        Locale.current.localizedString(forRegionCode: country.regionCode) ?? country.regionCode
    }
}
