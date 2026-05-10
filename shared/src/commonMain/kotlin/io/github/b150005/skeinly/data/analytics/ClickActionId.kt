package io.github.b150005.skeinly.data.analytics

/**
 * Phase 39.3 (ADR-015 §6) — closed taxonomy of user-clicked actions.
 *
 * Distinct from the typed [AnalyticsEvent] variants like
 * [AnalyticsEvent.ProjectCreated] / [AnalyticsEvent.ChartEditorSave] which
 * register **outcomes** (the action succeeded — a row landed in the DB).
 * `ClickAction` registers **intents** — the user tapped the button —
 * regardless of whether the underlying operation went on to succeed,
 * silently no-op, or surface an error.
 *
 * Concretely: tapping "Mark complete" on a project that's already
 * COMPLETED fires `ClickAction(MarkComplete, ProjectDetail)` but does not
 * fire any outcome event because the ViewModel's status guard short-
 * circuits the write path. The pair gives us engagement-vs-success
 * funnel visibility.
 *
 * Same cardinality discipline as [Screen] — closed enum, [wireValue] is
 * the stable PostHog string. New entries land alongside the call site in
 * the same commit; keeping the IDE's auto-completion list tight matters
 * more than alphabetical strictness.
 */
enum class ClickActionId(
    val wireValue: String,
) {
    // Project list / detail
    CreateProject("create_project"),
    IncrementRow("increment_row"),
    DecrementRow("decrement_row"),
    MarkComplete("mark_complete"),
    Reopen("reopen"),

    // Pattern library / edit
    SavePattern("save_pattern"),

    // Discovery
    Fork("fork"),

    // Chart authoring + branching
    SaveChart("save_chart"),
    UndoChart("undo_chart"),
    RedoChart("redo_chart"),
    SelectPaletteSymbol("select_palette_symbol"),
    SwitchBranch("switch_branch"),
    CreateBranch("create_branch"),
    ViewHistory("view_history"),

    // Pull requests
    OpenSuggestion("open_pull_request"),
    ApplySuggestion("apply_suggestion"),
    CloseSuggestion("close_pull_request"),

    // Onboarding
    SubmitOnboarding("submit_onboarding"),

    // Paywall / subscription (Phase 41.3)
    // SubscribeToPro is reserved for the Settings → Subscribe-to-Pro entry
    // (lands in Phase 41.3b alongside the Settings ListItem wiring) — it
    // captures the engagement intent BEFORE the paywall sheet opens, distinct
    // from PaywallOpened(trigger=Settings) which captures the outcome of the
    // tap (sheet successfully surfaced). Keeping it in the closed taxonomy
    // here makes the 41.3b slice a one-line ClickAction emit.
    SubscribeToPro("subscribe_to_pro"),
    SelectPaywallPackage("select_paywall_package"),
    ConfirmPurchase("confirm_purchase"),
    RestorePurchases("restore_purchases"),
}
