# AGENTS.md — how work is delivered in this repository

## The loop

```
user captures a raw idea, with a screenshot if useful   (board web UI, no ceremony)
user names the task id: "refine HOU-33"                 (this is what pulls the board)
agent: show <ID> --json, read attachments, grill with the user
agent: note the spec and the decisions on the user's task, link dependencies,
       create child tasks for work that has become real
user approves; agent: start <ID> → create_goal(that scope)
work → verify → auditor approves → finish <ID> --comment "<evidence>"  → in_review
user: drag In Review → Done
```

Work that cannot proceed does not wait in chat: `move <ID> blocked --comment "<what I need>"`.
A blocked task outlives the session; a chat question does not.

## Who owns what

| System | Owns | Lifetime |
| --- | --- | --- |
| grill | elicitation: questions, alternatives, decisions, spec | one conversation |
| `create_goal` | the plan for the work in flight, its budget, its completion gate | one session |
| kanboard | the durable ledger: status, priority, dependencies, evidence, human gate | across sessions |
| auditor | verification of one completion claim | one claim |

Rules that follow from that division:

- **The user captures, the agent refines additively.** Append with `note`, `attach` and `link`; do
  not rewrite a user's title or body (the CLI refuses it anyway). When refinement changes something
  only `edit` can set, add a refined child task and let the user cancel the raw one.
- **`in_review` is the human half of the auditor gate.** The auditor verifies the claim, the user
  closes it. `done` is reachable only from `in_review`, and only by the user.
- **One board task per goal.** `start <ID>` is the moment work becomes a claim; the goal is created
  with that task's scope, and the goal's steps are not mirrored onto the board. Grilling and research
  consume no `-do` slot. When a claim is real but no goal exists — because `create_goal` was not
  requested, or because the claim could not be made — say so out loud instead of working on silently.
- **The `finish` comment is the report the reviewer reads.** It carries the evidence: commits, files,
  commands, observed output, and what was deliberately not done.
- **Docs are the frozen record, the board is the only mutable status.** Status is never written into
  both.

## Board mechanics

The board is a per-machine, gitignored kanboard; its slug is `houseepg-3c5746` and the binary is not
on `PATH`:

```shell
C:\Users\anti\.pi\agent\npm\node_modules\@pi-unipi\kanboard-win32-x64\bin\unipi-kanboard.exe \
  --actor agent --project houseepg-3c5746 <command>
```

Writes are budgeted per `/unipi:kanboard-do` batch. When a write is refused with
`kanboard write budget used up`, say so and ask the user to run `/unipi:kanboard-do`; do not work
around it. Reads, and `finish`/`note`/`blocked` on a claim this session already made, stay free.

## Verification before a completion claim

- `./gradlew test`
- `./gradlew integrationTest`
- `cd frontend && npx tsc --noEmit && npm run build`, then copy `frontend/dist/*` into
  `src/main/resources/static/` when the change touches the web interface.
- Commit messages: a one-line summary, then `Change`, `Decisions` and `Verification` sections, with
  the commands that were run and what they printed. The commit message is evidence, not a changelog
  entry.
- Leave the machine as found: stop background servers, restore moved configuration files, and delete
  temporary fixtures.

## When this file and a task conflict

The user's instruction in the conversation wins. If a rule here is wrong or has become stale, say so
and propose the edit rather than silently ignoring it.
