---
title: "Append-Only / Merge-Only File Invariants"
aliases: [append-only, merge-only, safe-config-modification]
tags: [patterns, automation, safety]
sources:
  - "daily/2026-04-29.md"
created: 2026-04-29
updated: 2026-04-29
---

# Append-Only / Merge-Only File Invariants

When automation scripts modify shared configuration files (`.gitignore`, `settings.json`, etc.), they should follow append-only or merge-only invariants: never overwrite or remove existing content, only add new entries or merge new keys into existing structure. This prevents automation from destroying user customizations.

## Key Points

- `.gitignore` modifications should be append-only — check if entry exists, add only if missing, never rewrite the file
- JSON config files (e.g., `settings.local.json`) should be merge-only — deep-merge new keys into existing structure, preserve all existing keys
- Conditional modification: only update `.gitignore` in git repositories (`IS_GIT=1`), skip for non-git directories
- Idempotency follows naturally: running the same append/merge operation twice produces the same result
- The user retains full control over the file's existing content; the automation only extends it

## Details

The append-only invariant for line-based files like `.gitignore` is implemented by first checking whether each target line already exists in the file (e.g., via `grep -qxF`), then appending only missing lines. This is strictly safer than template-based approaches that write a complete file, because it preserves any manual entries the user has added.

For structured files like JSON, the merge-only invariant uses deep merge semantics: new keys are added, existing keys are preserved, nested objects are recursively merged. In the `/wiki-init` skill context, `settings.local.json` hook configurations are merged into whatever hooks the user already has configured, rather than replacing the entire hooks section.

An important boundary condition is that `.gitignore` should only be modified when the project is actually a git repository. The `/wiki-init` skill checks `IS_GIT=1` before touching `.gitignore`, leaving the decision to the user for non-git directories. This respects the principle that automation should not make assumptions about the user's version control intentions.

These invariants are particularly important for skills and setup scripts that may run multiple times (re-installation, updates, migrations). Without them, each run risks destroying user customizations, leading to a pattern where users fear running setup tools.

## Related Concepts

- [[concepts/claude-code-skills]] - Primary context: skills that modify project configuration files
- [[concepts/dry-run-mental-simulation]] - Invariant violations are detectable via mental dry-run against projects with existing configs

## Sources

- [[daily/2026-04-29.md]] - Append-only for `.gitignore`, merge-only for `settings.local.json`, conditional git-only modification
