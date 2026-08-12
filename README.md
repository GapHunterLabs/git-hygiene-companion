# Git Hygiene Companion

IntelliJ-family plugin. Lightweight inline git blame — author and date
at the end of the current line — designed from the ground up to never
block the editor.

## Why it exists

Born from real evidence in GitToolBox's own public issue tracker
(~10.2M downloads, 4.7★ freemium — the largest install base found while
researching this niche), not assumptions:

- "Extreme slowdown on startup" — the mouse pointer became unresponsive
  for roughly a minute on a real 35-module repository.
- IDE freezes tied to the plugin's own rebase/workspace-update hook,
  reported across multiple releases.
- A user who had *purchased* the paid tier reporting IntelliJ stuck/hung
  because of the plugin (2026-02): "Pls fix this problem when you have
  time. I have purchased your plugin."

## Why built this way

- **Every git operation runs off the EDT, by construction, via a real
  `git` subprocess.** Same `GeneralCommandLine`/process-handler pattern
  already proven in this workspace's React Native Companion (built to
  fix an analogous "severely impacting IDE performance" complaint) — this
  is not an optimization bolted onto a design that already blocks, it's
  the structural starting point.
- **A two-key cache, not a naive "recompute on every keystroke."** A
  cached blame result for a file stays valid as long as (a) the repo's
  current HEAD commit hasn't moved AND (b) the file's own last-modified
  timestamp hasn't changed. Switching to a different file, or committing
  in a completely unrelated file/module, changes neither key for files
  that weren't touched — the direct, structural fix for the cited freeze
  pattern.
- **No `git4idea` dependency.** Every git interaction — blame, resolving
  HEAD — shells out to the real `git` binary, keeping the entire
  git-interaction surface inside one well-understood, already-proven-safe
  pattern instead of mixing in a second, less-familiar platform VCS API
  under time pressure.
- **Inline blame only in v0.1, deliberately** — not branch/ahead-behind
  status too. This plugin's entire value proposition rests on a subtle
  correctness property (never block, always invalidate correctly), which
  deserved a full, unhurried verification pass rather than being split
  across more surface area.

## Usage

Open any file inside a git repository — inline blame (author, date)
appears at the end of each line automatically. Toggle it off under
Settings > Tools > Git Hygiene Companion.

## Enterprise / Team Licensing

Need enterprise features, custom git workflows, or team licensing?
Contact us at **gaphunterlabs@gmail.com**.

## Development

```
./gradlew test           # unit tests
./gradlew buildPlugin    # generates build/distributions/*.zip
./gradlew verifyPlugin   # checks compatibility against real IDEs
```

`demo/` is a real, `git init`'d repository with several real commits
under different simulated authors/dates — blame has nothing to show
without real git history, so this is genuine repo setup, not build
output.

## License

Apache-2.0. See `LICENSE`.
