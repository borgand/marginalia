# Morning handoff — Phase 1 core sidecar (overnight run, 2026-06-13)

Branch: `feat/phase1-core-sidecar` (main untouched). Every step is a separate
conventional commit if you want to review one by one.

## What's built (all of PRD Phase 1 except PTY auto-dispatch)

| PRD | Status |
|---|---|
| F1 comments + anchoring + persistence | done, 8 tests |
| F2 queue, auto/manual dispatch | done, 5 tests |
| F3 MCP server :4747 + 5 tools | done, 10 tool tests (HTTP transport untested headless — D11) |
| F4 hook + active-docs.json | done, hook verified standalone, merger has 4 tests |
| F5 merge engine, user wins | done, 11 tests incl. concurrent-typing acceptance scenario |
| F6 disk-write fallback | done (protect-and-merge policy, D13), 6 tests |
| F7 tool window | done (queue table, log, toggle, submit review) |
| F8 PTY dispatch | **deferred** (D6) — `/marginalia` slash command shipped instead |

49 tests green. `verifyPlugin` passes against IU 2025.2 / 2025.3 / 2026.1 / 2026.2
(only "experimental API" infos remain). `buildPlugin` produced
`build/distributions/marginalia-0.0.1.zip` (10 MB, no bundled coroutines).

## What I could not verify headless (your 10-minute morning test)

1. `./gradlew runIde`, open any project, open the **Marginalia** tool window —
   status should read `running on http://127.0.0.1:4747/mcp`.
2. In a terminal: `claude mcp add --transport http marginalia http://localhost:4747/mcp`
   (once), then in the sandbox IDE: Tools → *Marginalia: Install Claude Code Integration*.
3. Open a markdown file, select a sentence, <kbd>Cmd+Alt+M</kbd>, type a comment.
4. In Claude Code: `/marginalia` — it should pull the comment, edit the live buffer
   (you'll see the change land while the file stays open), and resolve it.
5. While it works: keep typing in the same file. Your edits must survive; conflicting
   agent hunks must show up as conflicts in the tool window log, not overwrite you.
6. Ask Claude to use its native `Edit` on the co-edited file — the hook must block it
   (needs `jq` on PATH).

## Decisions needing your eyes

`docs/decisions.md` — D1–D14. The opinionated ones: D3 (app-level server),
D6 (no PTY yet), D7 (no edit-distance fuzzy re-anchor yet), D9 (hook requires jq,
fail-open), D13 (conflict on external write restores user buffer wholesale).

## Known gaps / next steps

- PTY auto-dispatch (F8 proper) — needs interactive testing against a live TUI.
- Fading highlight on agent-applied ranges — basic highlights exist for comments;
  the "flash on applied hunk" polish is not wired yet.
- No Settings UI for the port (PropertiesComponent key `marginalia.mcp.port`, D4).
- JDK: builds used Homebrew OpenJDK 21 (`/opt/homebrew/opt/openjdk@21/...`) —
  your `JAVA_HOME` pointed at a removed zulu-17; consider fixing your shell profile.
