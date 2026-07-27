---
name: gui-owl-navigation
description: Compact on-device policy for screenshot-grounded Android navigation and popup dismissal.
---

# GUI-Owl Navigation

- Decide one atomic action from the current screen, then verify the newer screen.
- On a launcher, click a visible target match. If none exists and no search is
  exposed, use one safe central upward swipe to reveal the app collection.
- Click a visible search control, type the requested app name only after an
  editable field appears, then click a strong matching result.
- Do not select an unrelated icon, repeat Home/Back on an unchanged launcher,
  or claim success while the launcher is still visible.
- In a WebView or popup, use the screenshot to click the visible close/X
  control. A dispatched tap is not success; inspect the next screen.
- If the screen did not change, choose a different evidence-grounded recovery.
