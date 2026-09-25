# Notice Tracker UI Verification — v0.7.0 checklist build

This document records the final screen-by-screen verification against the 10 requested UI quality sections.

## Global design-system foundation

- [x] Single source of truth: `ui/UiTokens.kt`, `ui/UiKit.kt`, semantic theme colors, and Material theme styles.
- [x] Spacing scale is only 4 / 8 / 12 / 16 / 24 / 32dp in screen code.
- [x] Type scale is restricted to display / headline / title / body / label / caption.
- [x] Unified outline icon family (`ic_nt_*`) is used across app-owned screens.
- [x] Light and dark semantic token variants exist for primary, secondary, surface, outline, success, warning, and error roles.
- [x] Shared components define cards, primary/outlined buttons, state panels, typography, surfaces, and feedback.
- [x] CI regression audit rejects raw text sizes, arbitrary dp literals, legacy icon families, Toasts, and hard-coded screen colors.
- [x] Main light/dark and semantic text/background contrast pairs exceed WCAG AA.
- [x] No pure black-on-pure-white base palette.
- [x] Content motion uses shared 200ms motion token and respects Android animator-disable/reduce-motion behavior.
- [x] Platform dialogs, sheets, and activity transitions use Material/Android-native motion conventions.

## Screen-by-screen audit

Legend for each screen:
1 Foundation · 2 Consistency · 3 Touch/Layout · 4 Navigation · 5 Feedback/States · 6 Typography · 7 Color/Theming · 8 Motion · 9 Forms/Input · 10 Platform

### 1. Splash / app launch
- [x] 1 — Uses semantic surface/primary tokens and adaptive app icon.
- [x] 2 — Matches app theme and icon system.
- [x] 3 — System-owned splash layout handles insets/screen sizes.
- [x] 4 — Enters Desk directly; no dead end.
- [x] 5 — Initial Desk shows a designed loading state while local data is read.
- [x] 6 — No app body text on splash.
- [x] 7 — Light/dark splash background follows semantic surface tokens.
- [x] 8 — Android SplashScreen API handles native transition.
- [x] 9 — N/A: no input.
- [x] 10 — Uses Android 12+ SplashScreen conventions plus adaptive launcher icon.

### 2. First-launch onboarding sheet
- [x] 1 — Shared spacing/type/icon/button tokens.
- [x] 2 — Same Material sheet/button treatment as notice details.
- [x] 3 — 48dp+ actions, 8dp action spacing, scroll-safe sheet.
- [x] 4 — Clear Scan first notice / Not now exits; system back dismisses.
- [x] 5 — Notification permission is deferred until onboarding finishes.
- [x] 6 — Body uses 16sp shared BODY scale; two weights only.
- [x] 7 — Semantic theme roles; works in dark mode.
- [x] 8 — Material BottomSheet motion.
- [x] 9 — N/A.
- [x] 10 — Material 3 bottom sheet.

### 3. Desk screen
- [x] 1 — Shared page, type, spacing, button, card, and state components.
- [x] 2 — Same toolbar/card/button/state patterns as other primary screens.
- [x] 3 — Insets applied; all primary actions 48dp+; 8dp gaps; long text ellipsizes.
- [x] 4 — Desk tab highlighted; primary Scan action is one tap away.
- [x] 5 — Initial loading, attention empty state, recent empty state, async lookup progress.
- [x] 6 — Body >=16sp; labels/captions reserved for metadata; two weights.
- [x] 7 — Primary accent only for interactive emphasis; semantic roles centralized.
- [x] 8 — 200ms content fade with reduce-motion check.
- [x] 9 — Manual CNR opens a labeled validated form.
- [x] 10 — Material toolbar, bottom navigation, buttons, cards.

### 4. Notices screen
- [x] 1 — Shared tokens/components.
- [x] 2 — Notice cards share one card/status/action design.
- [x] 3 — 48dp filters/actions; 8–12dp card/action spacing; responsive 2-column Served/Unserved actions.
- [x] 4 — Notices tab highlighted; toolbar back and system Back both return to Desk.
- [x] 5 — Filter-specific empty states; fetch loading; fetch error state with Retry; save feedback.
- [x] 6 — Long case/court/server text ellipsizes; body/label roles are consistent.
- [x] 7 — Pending=warning, Served=success, Unserved=secondary completed state.
- [x] 8 — Shared content transition.
- [x] 9 — N/A directly; assignment uses dialog.
- [x] 10 — Material toggle group, cards, progress indicators.

### 5. Notice-details bottom sheet
- [x] 1 — Shared token/card/type/action system.
- [x] 2 — Same status chips/actions as Notices screen.
- [x] 3 — Explicit 48dp close; action spacing >=8dp; sheet is scrollable.
- [x] 4 — Close affordance and system back both dismiss the sheet.
- [x] 5 — Retry state appears for failed lookup; status/assignment saves show loading + success feedback.
- [x] 6 — Detail labels and body use shared scales; long values remain readable in scroll.
- [x] 7 — Semantic status colors use shared roles in light/dark.
- [x] 8 — Material BottomSheet transition.
- [x] 9 — Assignment delegated to a labeled selection dialog.
- [x] 10 — Material 3 bottom sheet and buttons.

### 6. QR scanner
- [x] 1 — Scanner-specific colors/sizes live in shared tokens/colors; unified `ic_nt_*` outline icons.
- [x] 2 — Close/flash/photo controls use one shared circular control treatment.
- [x] 3 — 56/64dp controls, system-inset padding, photo control bottom-center, no overlap.
- [x] 4 — Close and system Back both exit scanner; photo is directly reachable.
- [x] 5 — Camera loading indicator; photo decode loading; permission/camera/read errors have recovery actions.
- [x] 6 — Instruction text uses shared BODY scale.
- [x] 7 — Scanner semantic scrim/control/text/accent tokens; no raw colors.
- [x] 8 — No decorative motion; loading motion only.
- [x] 9 — N/A.
- [x] 10 — CameraX + Activity Result APIs, Material progress/Snackbar feedback.

### 7. eCourts lookup loading screen
- [x] 1 — Shared app bar, spacing, typography, cards and progress tokens.
- [x] 2 — Matches app-owned loading/error visual language.
- [x] 3 — Edge-to-edge safe insets; scroll/viewport-safe layout.
- [x] 4 — Toolbar cancel affordance exits lookup; caller converts failure to retryable notice state.
- [x] 5 — Designed progress + skeleton; network/server failure becomes retryable notice error state.
- [x] 6 — Shared title/body scales.
- [x] 7 — Semantic surface/text roles in light/dark.
- [x] 8 — Purposeful indeterminate loading only.
- [x] 9 — N/A during automatic lookup.
- [x] 10 — Material toolbar/progress; WebView kept hidden during automatic lookup.

### 8. Manual CAPTCHA recovery state
- [x] 1 — App-owned chrome uses shared tokens.
- [x] 2 — Same eCourts toolbar and feedback treatment.
- [x] 3 — WebView fills safe content region.
- [x] 4 — Toolbar cancel + system Back provide escape.
- [x] 5 — Persistent Snackbar explains required action and offers Cancel.
- [x] 6 — App-owned guidance uses shared typography.
- [x] 7 — App chrome is themed; external eCourts web content is controlled by eCourts.
- [x] 8 — Native WebView/page behavior; no decorative app motion.
- [x] 9 — CAPTCHA field itself belongs to external eCourts page; app does not replace or obscure it.
- [x] 10 — Standard WebView fallback for unavoidable external form.

### 9. Manual CNR dialog
- [x] 1 — Shared outlined text field/tokens.
- [x] 2 — Same Material dialog and field treatment as backend/add-server forms.
- [x] 3 — Material dialog actions meet platform target sizing.
- [x] 4 — Cancel/system Back return to prior screen.
- [x] 5 — Add disabled until valid; inline specific validation.
- [x] 6 — BODY scale input, always-visible floating label.
- [x] 7 — Theme-aware Material colors.
- [x] 8 — Material dialog motion.
- [x] 9 — Correct text/caps keyboard; exact 4-letter + 12-digit validation.
- [x] 10 — Material 3 outlined TextInputLayout.

### 10. Duplicate-notice dialog
- [x] 1 — Material shared dialog.
- [x] 2 — Same dialog treatment as other decisions.
- [x] 3 — Platform-sized actions.
- [x] 4 — Open existing / Add another / system Back all have clear outcomes.
- [x] 5 — No silent branch; chosen action proceeds visibly.
- [x] 6 — Material typography.
- [x] 7 — Theme-aware.
- [x] 8 — Material dialog motion.
- [x] 9 — N/A.
- [x] 10 — Material AlertDialog.

### 11. Assign-process-server dialog
- [x] 1 — Shared Material dialog.
- [x] 2 — Same selection pattern wherever assignment is invoked.
- [x] 3 — Platform list touch targets.
- [x] 4 — Cancel/system Back return to notice.
- [x] 5 — Assignment save shows loading then success Snackbar.
- [x] 6 — Material text roles.
- [x] 7 — Theme-aware.
- [x] 8 — Material dialog motion.
- [x] 9 — Single-choice input with current assignment selected.
- [x] 10 — Material single-choice dialog.

### 12. No-process-servers dialog
- [x] 1 — Shared dialog.
- [x] 2 — Same decision layout.
- [x] 3 — Platform actions.
- [x] 4 — Open Settings is a direct recovery path; Cancel/back escape.
- [x] 5 — Designed explanatory failure state, not raw error.
- [x] 6 — Material typography.
- [x] 7 — Theme-aware.
- [x] 8 — Material dialog motion.
- [x] 9 — N/A.
- [x] 10 — Material AlertDialog.

### 13. Process-server management dialog
- [x] 1 — Shared state panel, spacing, typography, buttons.
- [x] 2 — Same form/buttons as rest of app.
- [x] 3 — 48dp+ actions; list rows are spaced; dialog scrolls.
- [x] 4 — Done/system Back closes; Add action always available.
- [x] 5 — Designed empty state with Add action; add/remove confirmations and success feedback.
- [x] 6 — Shared BODY/LABEL scales.
- [x] 7 — Theme-aware.
- [x] 8 — Material dialog motion.
- [x] 9 — Add-server validation is inline and duplicate-aware.
- [x] 10 — Material dialog/components.

### 14. Add-process-server dialog
- [x] 1 — Shared outlined field.
- [x] 2 — Same field/dialog treatment as CNR/backend forms.
- [x] 3 — Platform action targets.
- [x] 4 — Cancel/system Back return to management.
- [x] 5 — Add disabled until valid; inline duplicate/length errors; success Snackbar.
- [x] 6 — BODY input with persistent label.
- [x] 7 — Theme-aware.
- [x] 8 — Material dialog motion.
- [x] 9 — Capitalized text keyboard; specific inline validation.
- [x] 10 — Material TextInputLayout/Dialog.

### 15. Backend setup dialog
- [x] 1 — Shared outlined field/tokens.
- [x] 2 — Same dialog/form language.
- [x] 3 — Platform action targets.
- [x] 4 — Cancel/system Back; Use default and Save both clear.
- [x] 5 — Save disabled until URL valid; inline URL error; backend check has progress and success/error feedback.
- [x] 6 — Shared body/caption roles.
- [x] 7 — Theme-aware.
- [x] 8 — Material dialog motion.
- [x] 9 — URI keyboard; explicit http/https + host validation.
- [x] 10 — Material 3 outlined form/dialog.

### 16. Visible-fields dialog
- [x] 1 — Shared Material dialog.
- [x] 2 — Same dialog styling.
- [x] 3 — Platform checkbox row touch targets.
- [x] 4 — Save/Cancel/system Back.
- [x] 5 — Save gives Snackbar confirmation.
- [x] 6 — Material typography.
- [x] 7 — Theme-aware.
- [x] 8 — Material dialog motion.
- [x] 9 — Multi-choice form retains current selections.
- [x] 10 — Material multi-choice dialog.

### 17. Appearance dialog
- [x] 1 — Theme choices map to centralized tokenized theme variants.
- [x] 2 — Same dialog styling.
- [x] 3 — Platform radio-row touch targets.
- [x] 4 — Selecting a theme applies and returns via recreation; system Back cancels.
- [x] 5 — Selection is immediately visible after recreation.
- [x] 6 — Material typography.
- [x] 7 — Every selectable theme defines full primary/secondary/surface/outline/error roles; semantic success/warning/error also have day/night tokens.
- [x] 8 — Native recreation/theme transition.
- [x] 9 — Single-choice selection.
- [x] 10 — Material single-choice dialog.

### 18. Export dialog + export feedback
- [x] 1 — Shared Material dialog/Snackbar/progress.
- [x] 2 — Same feedback patterns as save/backend actions.
- [x] 3 — Platform list targets.
- [x] 4 — Format choice continues to Android document destination; Back cancels.
- [x] 5 — Export shows indefinite loading; success Snackbar; failure Snackbar has Retry.
- [x] 6 — Material typography.
- [x] 7 — Theme-aware.
- [x] 8 — Material dialog/Snackbar motion.
- [x] 9 — Android document picker owns destination input.
- [x] 10 — Storage Access Framework CreateDocument contract.

### 19. Reminders info dialog
- [x] 1 — Shared Material dialog.
- [x] 2 — Consistent information-dialog pattern.
- [x] 3 — Platform action target.
- [x] 4 — Done/system Back.
- [x] 5 — Explains Pending vs complete reminder behavior.
- [x] 6 — Material typography.
- [x] 7 — Theme-aware.
- [x] 8 — Material dialog motion.
- [x] 9 — N/A.
- [x] 10 — Material AlertDialog.

### 20. App-info dialog
- [x] 1 — Shared Material dialog.
- [x] 2 — Consistent information-dialog pattern.
- [x] 3 — Platform action target.
- [x] 4 — Done/system Back.
- [x] 5 — No async operation.
- [x] 6 — Material typography.
- [x] 7 — Theme-aware.
- [x] 8 — Material dialog motion.
- [x] 9 — N/A.
- [x] 10 — Material AlertDialog.

## Small-screen/layout verification

- [x] Screen code contains no arbitrary dp literals; all dimensions use shared tokens.
- [x] Tappable app-owned controls use 48dp minimum or larger tokenized sizes.
- [x] Adjacent app-owned action buttons use at least the 8dp spacing token.
- [x] Long case number/title/court/process-server text uses maxLines + ellipsis where constrained.
- [x] Primary screens and lookup/scanner use system insets; scrollable content lives between app bar and bottom navigation.
- [x] Served/Unserved actions are two columns only; process-server assignment is full-width, preventing the earlier button wrapping.
- [x] Dialog/sheet content is scrollable where content can exceed compact displays.
- [x] Material platform-owned navigation/dialog rows retain Material 3 sizing rather than custom forced dimensions.

## Verification gates

- [x] Static UI regression audit added: `noticeapp/ui_audit.py`.
- [x] Android lint is configured in CI before assemble.
- [x] Debug APK assemble is configured after audit/lint.
- [x] Semantic contrast checked for body/secondary text and success/warning/error pairs in light and dark palettes; all checked pairs exceed 4.5:1.

## Screens updated

All app-owned screens and transient surfaces listed above were updated. No app-owned screen was intentionally skipped.

## External/system-owned surfaces not restyled

These are not skipped app screens; they are platform/external surfaces that the app does not own:
- Android runtime notification/camera permission dialogs — Android system UI.
- Android CreateDocument file picker — Storage Access Framework system UI.
- The eCourts CAPTCHA/search page shown only after automatic CAPTCHA attempts fail — external eCourts web content. The surrounding toolbar, safe area, feedback, and exit behavior are app-styled.

## One-off components

Two one-off components remain because their function cannot be represented by a generic app card/list component:
1. **Camera preview + scan frame overlay** — required for real-time QR acquisition. Its controls, sizes, colors, spacing, errors and feedback all use shared tokens/components.
2. **eCourts WebView** — required for eCourts/CAPTCHA flow. The app-owned loading/manual-recovery chrome uses shared tokens; the external webpage itself is not restyled.

No other app-owned component requires one-off visual styling.


## Final CI base workflow
- [x] PR base workflow updated to run UI audit, Android lint, debug assemble, then APK upload.
