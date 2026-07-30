---
name: gui-app-navigation
description: Find and open Android apps using registered device tools, then navigate them from current screen evidence.
---

# GUI App Navigation

Operate only through registered device tools. For a named installed app, prefer
registered `launch_app` with its visible Korean or English name. The Android
runtime resolves the package; never invent a package name, shell command, intent,
MCP action, or unregistered `open_app` action. Use `list_apps` only when the
visible app name is ambiguous or `launch_app` reports that it cannot resolve it.

At every step:

1. Inspect the latest screenshot, foreground package, UI-tree, and prior result.
2. Decide one next atomic action from the registered tools.
3. Prefer a matching UI node over coordinates.
4. Verify the visible result before deciding the following action.
5. Return `finish_success` only after the requested app is visibly open. Return
   `finish_failure` only after bounded, evidence-driven recovery proves it cannot
   be found.

## Visual overlays and WebViews

An accessibility tree can expose only one large `WebView` even though the
rendered image contains a dialog, advertisement, cookie banner, permission
prompt, coach mark, or other overlay. In that case the screenshot is the source
of truth:

1. Compare the requested goal with the current rendered screen.
2. Look near the overlay's corners and edges for a close icon, `X`, dismiss
   label, skip control, or outside-tap affordance.
3. If the control has a current UI node, use `tap_node`. Otherwise derive one
   coordinate from the current screenshot and call `tap`.
4. After the tap, inspect the newly captured screenshot. A successful gesture
   result alone does not prove that the overlay closed.
5. If the same overlay remains, choose a visibly different safe dismiss control
   or recovery action. Do not repeat the same coordinate blindly.
6. Return `finish_success` only when the newest screenshot visibly shows that
   the overlay is gone and the requested destination remains open.

If the screenshot shows a splash screen, loading indicator, or partially
rendered page, use one bounded `wait` and inspect the new screenshot instead of
guessing a coordinate on unstable content.

Do not assume that Android Back closes an overlay. Prefer a visible dismiss
control. If no safe close control is visible, `go_back` is a reversible fallback,
but its effect cannot be known in advance; always inspect the post-action screen.

When direct launch is unavailable or fails, explore the launcher as a user would. Possible
strategies include moving Home, opening the app collection/drawer with an
appropriate gesture, using a visible app-search control, entering the requested
name with `set_text`, paging or scrolling through app lists, and selecting a
matching result. Choose among these from the current screen; do not assume a
specific launcher layout, gesture direction, coordinate, language, or search UI.

Use this evidence-driven strategy ladder for named-app navigation:

1. Call registered `launch_app` with the visible app name.
2. If it cannot resolve the name, use a narrow `list_apps` query and retry with
   an exact returned label.
3. If direct launch is unavailable, move Home. On the launcher, first use any
   visible node whose label matches the target.
4. If the target is not visible, inspect the screenshot for a launcher search,
   app collection, page indicator, or drawer affordance. If none is exposed as
   a node, use one reversible central gesture derived from the current screen
   dimensions, then inspect the resulting screen.
5. When a visible search control exists, tap its current node. After the input
   becomes editable, use `set_text` with the requested app name.
6. Observe the filtered results and tap only a strong semantic label match.
7. Verify that the resulting foreground screen belongs to the requested app
   before returning `finish_success`.

Once the foreground package and screen show that Home/launcher is already open,
do not call `go_home` again. Advance to launcher exploration.
If no grounded target or visible search control exists on that launcher screen,
the next action is `swipe`, not `finish_success` and not an unrelated app icon.
Derive a safe central exploration gesture in the planner's normalized 0..1000
coordinate space: keep X near 500 and move vertically from roughly 750 to 250,
then verify the resulting screen.

Conversation messages, the user's own request, tool logs, status labels, and
other non-clickable text are evidence, not controls. Never tap them as a
substitute for the requested app or action.

An invented direct-launch request such as `open_app`, a package name, or an
intent is a planning error. `launch_app` is valid only because it is a registered
tool whose `name` argument is a visible app label.

If an action fails or the screen does not change, observe the new state and
choose a different recovery. Do not repeat an identical action without visible
evidence that retrying is appropriate.

Treat an app-name match as semantic: localized labels, spacing, and brand
variants may differ. Do not click a weak match when multiple candidates exist.

Return exactly one structured planner action and no prose while work remains.
