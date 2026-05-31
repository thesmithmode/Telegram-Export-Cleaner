---
title: "Dry-Run Mental Simulation"
aliases: [mental-dry-run, scenario-walkthrough, desk-checking]
tags: [testing, methodology, quality]
sources:
  - "daily/2026-04-29.md"
created: 2026-04-29
updated: 2026-04-29
---

# Dry-Run Mental Simulation

Dry-run mental simulation is the practice of mentally stepping through an automation script or skill line-by-line against a specific real-world scenario before executing it. This technique catches edge cases that unit tests and code review often miss because it grounds abstract logic in concrete filesystem and environment state.

## Key Points

- "Play the scenario in your head" — walk through every line of the script against a specific target environment (e.g., a real project directory)
- Effective at finding edge cases that arise from interactions between preconditions, filesystem state, and execution order
- Found 2 real gaps in `/wiki-init` skill: missing `mkdir -p .claude` and lack of stop-on-error behavior
- Complements but does not replace actual testing — catches structural issues before the first live run
- Most effective when the target environment is real (not hypothetical) and well-understood by the reviewer

## Details

The technique works because automation scripts interact with external state (filesystem, git, config files) in ways that are hard to model abstractly. By choosing a specific, real target environment and mentally executing each command against that known state, the reviewer surfaces concrete questions: "Does `.claude/` exist in this project? No — the script will fail at line 47."

During the `/wiki-init` skill development, a dry-run against the Telegram-export-clean project revealed two concrete gaps. First, the skill assumed `.claude/` directory already existed in the target project, which is not guaranteed for projects that haven't used Claude Code settings before. Second, the script lacked stop-on-error semantics (`set -e` equivalent), meaning a failed step would allow subsequent steps to execute against invalid state.

The key advantage over traditional code review is specificity. Code review asks "could this fail?" — mental simulation asks "will this fail in project X?" The latter is more constrained and therefore more productive for scripts that manipulate concrete environments. The technique scales poorly to complex branching logic but excels for linear setup scripts with 10-50 steps.

## Related Concepts

- [[concepts/claude-code-skills]] - Primary application context: validating skills before live execution
- [[concepts/git-check-ignore-gotchas]] - Specific edge case discovered via this technique
- [[concepts/append-only-file-invariants]] - Invariant violations are easily caught by mental simulation against existing config state

## Sources

- [[daily/2026-04-29.md]] - Technique applied to `/wiki-init` skill, found 2 gaps (missing `mkdir -p .claude`, no stop-on-error) plus the `git check-ignore` trailing slash bug
