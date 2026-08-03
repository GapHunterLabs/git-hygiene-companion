# Known issues

## Compile-time bug found and fixed during development

`GitBlameLinePainter.kt` (package `ui`) called
`GitHygieneCompanionRuntimeSettings` (defined in package `settings`)
without importing it -- `./gradlew test` failed at `compileKotlin` with
`Unresolved reference 'GitHygieneCompanionRuntimeSettings'`. Fixed by
adding the missing import. Caught by the very first attended-free test
run, before any manual smoke pass would have been needed.

## Critical bug found during manual runIde smoke testing, fixed (2026-08-03)

The one manual verification step this plugin still needed -- actually
opening a real file in a real IDE and watching inline blame appear --
caught something none of the automated tests did: `getLineExtensions`
(this plugin's whole reason for existing is that this method must never
block the EDT) was calling `GitHeadResolver.resolve()` -- a real `git
rev-parse HEAD` subprocess -- **directly on the EDT**, before ever
checking a cache. The platform's own threading guard caught this as
"Synchronous execution on EDT", firing 23 times in a single session
opening one file. `GitBlameRunnerNonBlockingTest` (written the night this
plugin was built) exercised `GitBlameRunner` in isolation on a background
thread and never called the real `getLineExtensions` path, so it could
not have caught this.

Fix: added `HeadCommitCache` (`cache/HeadCommitCache.kt`), populated
exclusively from `GitBlameBackgroundTask` (never from the EDT).
`getLineExtensions` now only ever reads this cache; a miss schedules the
same background task that was already resolving HEAD and filling
`BlameCache`, so no new background work was introduced.

Fixing bug #1 exposed a second, previously-unreached bug in the same
code path: the background task's completion callback called
`PsiManager.findFile` outside a read-action, which the platform's own
threading assertions also caught (only reachable once the EDT-blocking
call was removed and the background path actually ran to completion for
the first time in a real IDE). Fixed by wrapping that call in
`ReadAction.run`.

A new regression test, `GitBlameLinePainterTest`, covers both: cold-cache
calls must never throw a threading violation, and warm-cache calls read
`HeadCommitCache`/`BlameCache` synchronously without ever touching git
again. All 15 tests pass; `verifyPlugin` re-run 6/6 "Compatible" after
the fix.

## Non-blocking: deprecated API usage on IDE 253/261/262

`verifyPlugin` reports deprecated API usage on the three newest target
IDEs (253.x, 261.x, 262.x):

```
Deprecated method com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.restart(PsiFile) invocation
Deprecated method com.intellij.openapi.application.ReadAction.run(ThrowableRunnable) invocation
```

`DaemonCodeAnalyzer.restart(PsiFile)` is used in
`GitBlameLinePainter.scheduleBackgroundBlame`'s completion callback to
trigger a repaint after a background blame computation finishes --
confirmed during design as the idiomatic trigger for "line extension
data changed, please repaint" (no narrower dedicated event exists for
`EditorLinePainter`). `ReadAction.run(ThrowableRunnable)` is the fix for
the read-action bug above -- functional, just superseded by a
Kotlin-friendlier overload on newer platform versions.
`DEPRECATED_API_USAGES` is deliberately excluded from this plugin's
`pluginVerification.failureLevel` (same as every other Gap Hunter Labs
plugin) and `SCHEDULED_FOR_REMOVAL_API_USAGES` is included and passed
clean on all 6 IDEs, so neither is a forced migration -- just a note for
a future release if the platform proposes narrower replacements.
