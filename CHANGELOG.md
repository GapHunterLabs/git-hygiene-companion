<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Git Hygiene Companion Changelog

## [Unreleased]

## [0.1.1]

### Fixed

- Marketplace listing icon not rendering (showed a broken "plugin icon"
  placeholder) — replaced with the same icon already proven to render
  correctly on other Gap Hunter Labs listings.

## [0.1.0]

### Added

- Inline git blame (author + date at end of line), computed entirely
  off the EDT via a real `git` subprocess -- never blocks the editor.
- Two-key cache (repo HEAD commit + file's own modification time):
  switching files or committing elsewhere never triggers a recompute.
- Toggle in Settings > Tools > Git Hygiene Companion.

### Known gaps

- Branch/ahead-behind status is deliberately out of scope for v0.1 (see
  README's "Why built this way") -- inline blame alone got the full,
  unhurried verification pass this plugin's safety-critical design
  deserves.

[Unreleased]: https://github.com/GapHunterLabs/git-hygiene-companion/compare/0.1.1...HEAD
[0.1.1]: https://github.com/GapHunterLabs/git-hygiene-companion/compare/0.1.0...0.1.1
[0.1.0]: https://github.com/GapHunterLabs/git-hygiene-companion/commits/0.1.0
