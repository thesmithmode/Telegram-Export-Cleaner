---
name: Task Completion Checklist
description: Steps to complete when finishing a task
type: project
---

# Task Completion Checklist

When a task is complete, verify the following:

## Code Changes

- [ ] **Code compiles** (Java: `mvn clean compile`)
- [ ] **Code follows style** (checkstyle: `mvn checkstyle:check`)
- [ ] **All public classes/methods have JavaDoc** (Java) or docstrings (Python)
- [ ] **Type hints added** (Python: all function signatures)
- [ ] **No debug code** (print statements, console.log, etc.)
- [ ] **No commented-out code** (delete, don't leave)
- [ ] **Error handling appropriate** (catch real errors, don't suppress)

## Commits

- [ ] **Commit message is in RUSSIAN** (FEAT:, FIX:, REFACTOR:, etc.)
- [ ] **Commit message is imperative** ("добавить", not "добавил")
- [ ] **NO Co-Authored-By trailers** in commit message
- [ ] **Commit is atomic** (one logical change)
- [ ] **Branch is dev or feature/\*** (never main)

## Testing

- [ ] **No `mvn test` run locally** (tests only in CI)
- [ ] **No `pytest` run locally** (tests only in CI)
- [ ] **PR created to dev** with test details in description
- [ ] **Tests added for new code** (JUnit 5, pytest)
- [ ] **CI passes** (check GitHub Actions workflow)

## Documentation

- [ ] **Relevant .md files updated** (ARCHITECTURE.md, API.md, etc.)
- [ ] **CLAUDE.md NOT committed** (.gitignore applies)
- [ ] **README.md updated if needed** (new features, setup changes)
- [ ] **Code comments explain WHY** (not WHAT)

## Pushing & PRs

- [ ] **Push to dev or feature/\*** (never main)
- [ ] **Create PR to dev** (not main)
- [ ] **PR title is short & descriptive** (English or Russian OK in title)
- [ ] **PR description explains changes** (what, why, testing)
- [ ] **All CI checks pass** (GitHub Actions green)
- [ ] **No merge to main until approved** (reviewed, CI green, ready)

## Merging to Main

- [ ] **PR approved by reviewer**
- [ ] **Merge strategy: --squash** (one clean commit on main)
- [ ] **Commit message on main:** follows FEAT:/FIX:/REFACTOR: format
- [ ] **Delete feature branch** after merge
- [ ] **Sync local dev:** `git checkout dev && git pull origin dev`

## Special Cases

### Database/Schema Changes
- [ ] **Migration reversible** (no data loss if rollback needed)
- [ ] **Backward compatibility maintained** or migration plan clear
- [ ] **SQLite cache compatibility checked** (Python worker)

### API Changes
- [ ] **Endpoint documented in docs/API.md**
- [ ] **Error responses documented** (HTTP codes, error JSON)
- [ ] **API key checks in place** (if applicable)

### Deployment/Config Changes
- [ ] **.env.example updated** (if new env vars)
- [ ] **docs/SETUP.md updated** (if new config needed)
- [ ] **Docker Compose updated** (if service changes)
- [ ] **No secrets in .git history** (never commit .env files)

## Final Verification

- [ ] **Task description matched** (solved the right problem)
- [ ] **No regressions** (existing features still work)
- [ ] **Edge cases handled** (null, empty, overflow, etc.)
- [ ] **Performance acceptable** (no O(n²) loops, etc.)
- [ ] **Memory leaks checked** (resources properly cleaned)

---

**Remember:** This is a RUSSIAN-first project. Code comments, commits, documentation should be in Russian by default (except English in public API docs/README if necessary for clarity).
