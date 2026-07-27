---
name: gui-app-navigation
description: Find and open Android apps through visible GUI controls using screenshots, UI-tree observations, text entry, taps, and swipes. Use for requests to locate, open, or switch to an app without package-based launch APIs.
---

# GUI App Navigation

Operate only through the registered atomic GUI tools. Never invent `open_app`,
`launch_app`, package-manager, shell, intent, or direct-start tools.

At every step:

1. Inspect the latest screenshot, foreground package, UI-tree, and prior result.
2. Decide one next atomic action from the registered tools.
3. Prefer a matching UI node over coordinates.
4. Verify the visible result before deciding the following action.
5. Call `finish` only after the requested app is visibly open, or after reasonable
   GUI exploration proves it cannot be found.

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
6. Call `finish` only when the newest screenshot visibly shows that the overlay
   is gone and the requested destination remains open.

If the screenshot shows a splash screen, loading indicator, or partially
rendered page, use one bounded `wait` and inspect the new screenshot instead of
guessing a coordinate on unstable content.

Do not assume that Android Back closes an overlay. Prefer a visible dismiss
control. If no safe close control is visible, `go_back` is a reversible fallback,
but its effect cannot be known in advance; always inspect the post-action screen.

When an app is not visible, explore the launcher as a user would. Possible
strategies include moving Home, opening the app collection/drawer with an
appropriate gesture, using a visible app-search control, entering the requested
name with `set_text`, paging or scrolling through app lists, and selecting a
matching result. Choose among these from the current screen; do not assume a
specific launcher layout, gesture direction, coordinate, language, or search UI.

Use this evidence-driven strategy ladder for named-app navigation:

1. If an unrelated app is foreground and it has no visible route to the target,
   use `go_home`.
2. On the launcher, first use any visible node whose label matches the target.
3. If the target is not visible, inspect the screenshot for a launcher search,
   app collection, page indicator, or drawer affordance. If none is exposed as
   a node, use one reversible central gesture derived from the current screen
   dimensions, then inspect the resulting screen.
4. When a visible search control exists, tap its current node. After the input
   becomes editable, use `set_text` with the requested app name.
5. Observe the filtered results and tap only a strong semantic label match.
6. Verify that the resulting foreground screen belongs to the requested app
   before calling `finish`.

Once the foreground package and screen show that Home/launcher is already open,
do not call `go_home` or `observe_ui` again. Advance to launcher exploration.
If no grounded target or visible search control exists on that launcher screen,
the next action is `swipe`, not `finish` and not an unrelated app icon. Derive a
safe central exploration gesture from `DEVICE_SCREEN_PX`: keep X near 50% of
screen width and move vertically from roughly 75% to 25% of screen height.
Use absolute pixel arguments calculated from those current dimensions, then
verify the resulting screen.

Conversation messages, the user's own request, tool logs, status labels, and
other non-clickable text are evidence, not controls. Never tap them as a
substitute for the requested app or action.

An invented direct-launch request such as `open_app` or `launch_app` is a
planning error, not a cue for the runtime to substitute a fixed workflow.
Re-plan from the latest visible screen using one registered atomic tool.

If an action fails or the screen does not change, observe the new state and
choose a different recovery. Do not repeat an identical action without visible
evidence that retrying is appropriate.

Treat an app-name match as semantic: localized labels, spacing, and brand
variants may differ. Do not click a weak match when multiple candidates exist.

Return exactly one tool-call JSON object and no prose while work remains.
