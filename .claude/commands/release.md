---
description: Create a new release with version bump, changelog, and tags
---

When the user invokes this command, create a new release by following these steps:

## Step 1: Verify Branch

Ensure you are on the `main` branch. If not, warn the user and stop — releases should only be cut from `main`.

```bash
git branch --show-current
```

## Step 2: Get Current Version

Determine the current version from git tags:

```bash
git describe --tags --abbrev=0
```

Parse the version number (e.g., `v0.1.13` -> `0.1.13`).

## Step 3: Ask User for Bump Type

Use the AskUserQuestion tool to ask the user which version component to bump:

- **patch**: Increment patch version (e.g., 0.1.13 -> 0.1.14)
- **minor**: Increment minor version and reset patch (e.g., 0.1.13 -> 0.2.0)
- **major**: Increment major version and reset minor/patch (e.g., 0.1.13 -> 1.0.0)

Calculate the new version (e.g., `v0.1.14`).

## Step 4: Collect Commits Since Last Tag

Get all commits since the last tag:

```bash
git log --oneline $(git describe --tags --abbrev=0)..HEAD
```

Parse the commit messages to understand what changed.

## Step 5: Update CHANGELOG.md

Read the existing `CHANGELOG.md` file and:

1. Add a new version section after `## [Unreleased]` and before the previous version
2. Use the format: `## [X.X.X] - YYYY-MM-DD` (use today's date)
3. Include a summary paragraph highlighting 2-3 most significant changes from a **user's perspective** (not developer perspective)
4. Categorize commits into sections as appropriate:
   - **Major Changes** - for significant new features or breaking changes (with subheadings if needed)
   - **Added** - for new features
   - **Changed** - for changes in existing functionality
   - **Fixed** - for bug fixes
   - **Removed** - for removed features
   - **Documentation** - for documentation updates
   - **Internal** - for internal refactoring (de-emphasize unless user-visible)

Focus on user-facing changes. Internal refactoring should be de-emphasized unless it has user-visible benefits.

## Step 6: Commit Documentation Changes

Stage and commit the changelog update:

```bash
git add CHANGELOG.md
git commit -m "Release vX.X.X"
```

Replace `X.X.X` with the actual new version number.

## Step 7: Create Version Tag

Create a new annotated tag for the release:

```bash
git tag -a vX.X.X -m "Release vX.X.X"
```

## Step 8: Push Changes and Tags

Push the commit and tags to the remote repository:

```bash
# Push the commit
git push

# Push tags
git push --tags
```

## Step 9: Create GitHub Release

Use `gh release create` to publish the release on GitHub. Use the same release notes from the CHANGELOG entry as the body:

```bash
gh release create vX.X.X --title "vX.X.X" --notes "RELEASE_NOTES_HERE"
```

## Step 10: Report to User

Display a summary to the user:
- The new version number
- Number of commits included in this release
- Confirmation that CHANGELOG.md was updated
- Confirmation that tags were created and pushed
- Link to the GitHub release

## Example Output to User

```
Release v0.1.14 created successfully!

Changes:
- 8 commits since v0.1.13
- CHANGELOG.md updated with release notes

Tags:
- Created tag: v0.1.14

Pushed to remote: origin

GitHub release: https://github.com/bhauman/clojure-mcp/releases/tag/v0.1.14
```

## Error Handling

- If there are uncommitted changes, warn the user and ask if they want to continue
- If no commits exist since the last tag, warn the user that there's nothing to release
- If git push fails, show the error and suggest the user check their remote access
- If CHANGELOG.md is missing, report the error clearly
