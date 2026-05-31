---
title: "git check-ignore Edge Cases"
aliases: [git-check-ignore, gitignore-validation]
tags: [git, edge-cases, debugging]
sources:
  - "daily/2026-04-29.md"
created: 2026-04-29
updated: 2026-04-29
---

# git check-ignore Edge Cases

The `git check-ignore` command has subtle behavior differences when checking paths that do not yet exist on the filesystem, particularly with directories. A trailing slash is required to correctly match directory-level gitignore rules against non-existent directories.

## Key Points

- `git check-ignore .memory` (no trailing slash) may fail to match a `.memory/` gitignore rule if the directory does not exist yet
- `git check-ignore .memory/` (with trailing slash) correctly triggers directory-matching rules regardless of filesystem state
- This distinction matters in automation scripts that check gitignore status before creating directories
- The bug was discovered during live testing of the `/wiki-init` skill and would have caused false negatives (reporting paths as not ignored when they should be)
- Always use trailing slash when checking directory ignore status programmatically

## Details

Git's `.gitignore` rules distinguish between files and directories. A rule like `.memory/` with a trailing slash only matches directories. When `git check-ignore` receives a path argument, it uses the filesystem to determine whether the path is a file or directory. If the path does not exist, git cannot make this determination automatically.

The fix is straightforward: always append a trailing slash when programmatically checking whether a directory path is covered by gitignore rules. This explicitly tells git to treat the path as a directory, bypassing the filesystem existence check. The pattern `git check-ignore -q .memory/` is the reliable cross-platform approach.

This edge case is particularly insidious in setup scripts and automation skills that run before the target directory has been created. The script checks "is this already ignored?" before adding a gitignore entry — if the check gives a false negative, the script adds a duplicate entry or proceeds with incorrect assumptions about the gitignore state.

## Related Concepts

- [[concepts/claude-code-skills]] - Bug was discovered during skill development and live testing
- [[concepts/dry-run-mental-simulation]] - This bug was caught during scenario play-through, not in production

## Sources

- [[daily/2026-04-29.md]] - Bug discovered during `/wiki-init` live testing: `git check-ignore` requires trailing slash for non-existent directories
