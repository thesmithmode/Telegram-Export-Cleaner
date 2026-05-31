---
title: "Claude Code Skills Architecture"
aliases: [skills, slash-commands, skill-system]
tags: [claude-code, automation, tooling]
sources:
  - "daily/2026-04-29.md"
created: 2026-04-29
updated: 2026-04-29
---

# Claude Code Skills Architecture

Claude Code skills are reusable automation units invoked via slash commands (e.g., `/wiki-init`). Each skill is a markdown file containing a prompt template that Claude Code loads and executes. Skills enable packaging complex multi-step workflows into a single repeatable command.

## Key Points

- Skills should be autonomous by default — minimize confirmation dialogs to only truly destructive or ambiguous cases (e.g., missing required tooling, paths already configured)
- Concise skills outperform verbose ones: a 290-line skill rewritten to ~80 lines proved more reliable and maintainable
- Skills should not auto-commit; the user controls git state explicitly
- Skills operate within the Claude Code permission model — they can use all available tools (Read, Write, Edit, Bash, etc.)
- Guard clauses at the top (precondition checks) prevent partial execution and leave the system in a clean state

## Details

The skill system in Claude Code allows packaging complex setup and maintenance workflows into single slash commands. A well-designed skill follows the principle of autonomy: it should complete its task without unnecessary user interaction. The only acceptable interruption points are when a required external dependency is missing (e.g., `uv` not installed) or when proceeding would overwrite existing user configuration.

Skill size directly correlates with reliability. Removing fluff such as troubleshooting sections, verbose confirmation dialogs, and explanatory comments from ~290 lines down to ~80 lines eliminated ambiguity in the execution path. Each line in a skill should either check a precondition, perform an action, or handle an error. The `/wiki-init` skill demonstrated this pattern: clone repo, configure paths, update gitignore, merge settings — no ceremony.

An important design constraint is that skills should never auto-commit changes to git. The rationale is separation of concerns: the skill handles file-system state, while the user (or a separate commit workflow) handles version control decisions. This prevents surprises in git history and respects the user's branching and commit message conventions.

## Related Concepts

- [[concepts/append-only-file-invariants]] - Skills that modify shared config files should use append-only/merge-only patterns
- [[concepts/dry-run-mental-simulation]] - Testing skills via mental dry-run before live execution
- [[concepts/git-check-ignore-gotchas]] - Edge case discovered during skill development

## Sources

- [[daily/2026-04-29.md]] - `/wiki-init` skill creation: rewrite from 290→80 lines, autonomy pattern, no auto-commit rule
