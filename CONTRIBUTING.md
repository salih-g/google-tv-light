# Contributing

The most useful contribution is a **plan set for another TV**: fork, add `plans/<brand-model>/`
with your lists, the firmware string from `tvclean.py probe`, and what you measured before and
after (`tvclean.py mem`). Say which remote keys you tested.

Rules that keep this safe for everyone:

- Never add `pm uninstall` of a system package to the tool. `remove` (uninstall for user 0) is the
  ceiling, and only for packages the system starts while disabled.
- Anything that can break inputs, the remote, casting, Assistant, Play services or updates goes in
  `protected.py`, not in a plan.
- Mutating commands stay dry-run by default and write a snapshot first.
- Launcher: no network permission, no background services, no dependencies beyond the Android SDK.

Commit messages follow Conventional Commits (`feat(launcher): …`, `fix(tvclean): …`).
