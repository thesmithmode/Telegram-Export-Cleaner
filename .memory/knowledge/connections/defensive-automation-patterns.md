---
title: "Connection: Defensive Automation Patterns"
connects:
  - "concepts/dry-run-mental-simulation"
  - "concepts/append-only-file-invariants"
  - "concepts/claude-code-skills"
sources:
  - "daily/2026-04-29.md"
created: 2026-04-29
updated: 2026-04-29
---

# Connection: Defensive Automation Patterns

## The Connection

Three concepts — mental dry-run simulation, append-only/merge-only file invariants, and autonomous skill design — form a coherent defensive pattern for building reliable automation. Each addresses a different failure mode: dry-run catches logic errors before execution, file invariants prevent data destruction during execution, and skill autonomy minimizes the interaction surface where human error can enter.

## Key Insight

The non-obvious relationship is that these three techniques are mutually reinforcing. Append-only invariants make dry-run simulation tractable (the reviewer only needs to verify that new content is added, not reason about what existing content might be destroyed). Dry-run simulation is most effective when the script follows simple invariants (linear execution, no destructive operations). And autonomous skills benefit from both: fewer interaction points means fewer states to simulate, and safe file operations mean the skill can proceed without confirmation dialogs.

The combination was validated empirically: the `/wiki-init` skill rewrite applied all three patterns simultaneously. The result was an 80-line skill that could be tested via mental dry-run in under 5 minutes and safely re-run without destroying existing configuration.

## Evidence

During the `/wiki-init` development session:
- Mental dry-run against Telegram-export-clean found 2 real gaps (missing dir creation, no stop-on-error)
- Append-only `.gitignore` and merge-only `settings.local.json` eliminated an entire class of "will this break my existing config?" concerns
- Removing confirmation dialogs (autonomy) reduced the skill from 290 to 80 lines, making dry-run verification practical
- The `git check-ignore` trailing slash bug was caught by dry-run precisely because the invariant question was simple: "is this path already in .gitignore?"

## Related Concepts

- [[concepts/dry-run-mental-simulation]]
- [[concepts/append-only-file-invariants]]
- [[concepts/claude-code-skills]]
- [[concepts/git-check-ignore-gotchas]]
