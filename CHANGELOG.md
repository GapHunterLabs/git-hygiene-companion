<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Git Hygiene Companion Changelog

## [Unreleased]

## [0.1.2]

### Fixed

- After a commit, the lines it committed kept showing "Not Committed
  Yet": HEAD was resolved once per repository and never again. The
  cached HEAD is now tied to a cheap stamp of the files that change
  whenever HEAD moves (commit, checkout, reset, pull), so the blame is
  recomputed for the new commit. Right after a commit those files are
  updated in sequence, so the background task waits for them to settle
  before trusting the HEAD it read.
- A new file that wasn't committed yet never got its blame in that
  session, not even after committing it: the failed `git blame` left the
  file marked as "in progress" forever. It now gets an empty result for
  that HEAD, and is blamed again once it is committed.
- With unsaved edits, every annotation below an inserted or deleted line
  was shifted by one line (each line showed the author of the line above
  it). While a file has unsaved changes the annotations are now hidden;
  they come back, recomputed, as soon as the file is saved.
- The listing said the blame appears at the end of the current line; it
  appears at the end of each line.

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

[Unreleased]: https://github.com/GapHunterLabs/git-hygiene-companion/compare/0.1.2...HEAD
[0.1.2]: https://github.com/GapHunterLabs/git-hygiene-companion/compare/0.1.1...0.1.2
[0.1.1]: https://github.com/GapHunterLabs/git-hygiene-companion/compare/0.1.0...0.1.1
[0.1.0]: https://github.com/GapHunterLabs/git-hygiene-companion/commits/0.1.0
