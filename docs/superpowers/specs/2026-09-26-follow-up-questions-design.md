# Follow-up questions in the web chat — design

Date: 2026-09-26. Status: approved in conversation, section by section;
supplements SPEC.md §10, §11, §12, §14, §18.3 and §21 priority 1 (those
remain the authority once updated; this file fixes the choices).

## Goal

After an answer, the user asks a follow-up in the same conversation
("那主管呢？", "那病假呢？") and it is understood in context. The earlier
turns stay on the page and the conversation grows downwards.

Success: ask 「特休天數怎麼計算？」, then 「那病假呢？」; the second turn
retrieves and cites `hr/leave.md` § 病假. ACL, citation validation (§10.2)
and tracing behave exactly as today.

Decided with the user:

| Question | Decision |
|---|---|
| Where the conversation lives | **On the page only.** The browser keeps the turns and sends the recent ones with each follow-up; a reload or 「新對話」 clears it. No server-side storage, no `app.dtlv` schema change. Each turn still writes its trace. |
| How a follow-up becomes a query | **LLM rewrite** (condense question): the recent turns plus the follow-up go to the chat model, which returns one standalone question; an already standalone question comes back unchanged. |
| What the answer step sees | **Only the standalone question** and this turn's sources — the §10.1 prompt is unchanged. Earlier answers never reach the answer step, so every claim still has to come from this turn's sources. |
| API | `POST /api/v1/ask` accepts the same history (§12: the web page calls the same functions as the API). |

Out of scope: server-side conversations, a list of past conversations,
SSE streaming (T5.4), the OpenAI-compatible endpoint for LibreChat, an
eval set for follow-ups (eval does not call the chat model today; goes
to `docs/backlog.md`).

## Flow

```
no history  → exactly today's /ask (no rewrite call)
history     → rewrite(history[-3:], question)     ; chat, temperature 0
                → standalone query
                → pipeline/search + answer (§9, §10 unchanged)
```

## Components

### `llm/rewrite.clj` (new)

One job: `(rewrite! chat-fn history question) → {:query :ms :model :failed?}`.

- History input: the last **3** turns `{:query :answer}`. Before building
  the prompt each answer has its `[n]` citations removed (they point at
  sources the rewrite step does not see) and is cut to **500** characters;
  each query is cut to 1000.
- Prompt in `resources/prompts/rewrite.md`, read on every call like
  `answer.md`. It asks for one standalone question in the user's
  language, and for the question unchanged when it already stands alone.
  Turns go in a `<history>` block; history text is neutralized the same
  way passages are (`<history>` / `</history>` → full-width brackets).
- Chat parameters: `temperature 0`, `max_tokens 128`, the configured
  `:chat/extra-body`. The reply goes through `strip-think`.
- Output check: after trimming, the reply must be non-blank, at most 1000
  characters and one line (surrounding quotes are stripped). Otherwise
  `:failed? true` and `:query` is the original question.
- A chat dependency failure (ex-info with `:llm/endpoint`) is **not**
  caught: the answer call would fail the same way, so the request
  becomes the usual 503 with a failure trace.

### `llm/answer.clj`

`ask!` (`[deps principal query opts]`) reads an optional `:history` from
`opts` (a vector of `{:query :answer}`). With a non-empty history it calls `rewrite!`
first and searches/answers with the standalone query. The result gains
`:standalone-query` (only when a rewrite ran), `:stages :rewrite {:ms
:standalone-query :model :failed?}`, and `:rewrite-failed` in
`:degraded` when the output check failed. Without history nothing
changes.

### API: `POST /api/v1/ask`

- Body adds `history` (optional): `[{query: string 1–1000, answer:
  string ≤ 20000}]`, at most 20 items; a wrong shape is `400
  invalid_request`. The server uses the last 3 and truncates as above
  (being lenient with length keeps clients simple).
- Response adds `standalone_query` when a rewrite ran; `degraded` may
  contain `"rewrite_failed"`. Without `history` the response is
  byte-for-byte today's shape.

### Trace

`:trace/query` stays what the user typed (the follow-up). `:trace/stages`
gains `:rewrite {:ms :standalone-query :model :failed?}`. The history
itself is not stored (it is the user's own earlier questions and
answers, already in their earlier traces).

### Web UI (`web/ask.clj`, `/`)

```
┌──────────────────────────────────────────┐
│ 問答                           [新對話]  │
├──────────────────────────────────────────┤
│ 你：特休天數怎麼計算？                   │
│ ┌ answer [1] ────────────────────────┐   │
│ 來源 [1] 請假規定｜特休 …   開啟文件    │
│ 你：那病假呢？                           │
│   搜尋：病假的規定是什麼？      (grey)   │
│ ┌ answer [1] ────────────────────────┐   │
│ 來源 [1] …                               │
├──────────────────────────────────────────┤
│ [ 輸入問題…                    ] [送出]  │
│ □ Debug    產生回答中…                   │
└──────────────────────────────────────────┘
```

- HTMX stays; no new JS framework. The form posts to `/ask` with
  `hx-target="#conversation"` and `hx-swap="beforeend"`; each response is
  one turn fragment appended to the conversation, then scrolled into
  view. The form sits below the conversation.
- **History lives in the page**: every successful turn fragment carries
  hidden inputs `history_query` and `history_answer`; the form includes
  them (`hx-include`). The server takes the last 3 pairs. A failed turn
  (503, unexpected error) carries none, so it never enters history; a
  no-evidence turn does (the user may rephrase next).
- 「新對話」 is a link to `/`: a fresh page, empty conversation.
- Anchors are unique per turn: a turn's sources use `t<id>-src-<n>`,
  `<id>` being the first 8 hex characters of its trace id, so `[1]` in
  the third turn jumps to that turn's source 1. No counter is kept.
- After a send the textarea is cleared; the Debug checkbox keeps its
  state.
- A rewritten turn shows 「搜尋：<standalone query>」 in grey under the
  question. A failed rewrite shows 「無法理解追問，已直接用原問題搜尋。」
  instead.
- Each turn has its own Debug panel (as today, read from the stored
  trace) with one more row: the rewrite stage's ms and output.

## Error handling

| Case | Result |
|---|---|
| Chat endpoint fails during rewrite | 503 + failure trace (as a chat failure today); the web turn shows the error notice and does not enter history |
| Rewrite output unusable (blank, > 1000 chars, several lines) | Continue with the original question; `degraded: ["rewrite_failed"]`; web notice |
| `history` malformed (API) | 400 `invalid_request` |
| History too long | No error; last 3 turns, fields truncated |

## Security

History comes from the client and can be forged. That grants nothing:
it only shapes the query this user's own retrieval runs, which is the
same as the user typing that query, and retrieval still filters by the
user's groups (§9.3). Earlier answers were built from documents the user
could read; a forged answer is the user's own text. The rewrite prompt
carries only history and question, never passages. §18.3 gains:

- `/ask` with history as a user without access: citations, debug
  candidates and the answer prompt contain no restricted document;
- forged history naming a restricted document's title and opening text
  still retrieves nothing from it (negative control: the same history as
  admin does find it).

## Testing

- Unit (`rewrite`): prompt assembly, `[n]` removal, truncation, the
  output check (blank / too long / multi-line → original question),
  neutralizing `<history>`.
- `answer/ask!` with a stub chat: history → rewrite call then answer
  call, `:rewrite` stage, `:standalone-query`; no history → one call;
  unusable rewrite → `:rewrite-failed`.
- API: history schema (400), `standalone_query`, response unchanged
  without history, 503 on a rewrite dependency failure.
- Web: turn fragment carries the hidden fields and per-turn anchors; the
  submitted history reaches `ask!`; failed turns carry no hidden fields.
- §18.3 additions above.
- Real models (`:vllm`): 「特休天數怎麼計算？」 → 「那病假呢？」 rewrites to a
  question about 病假 and cites `hr/leave.md`. Measure the rewrite's
  added latency locally and record it in `docs/decisions.md`.
- `bb browser-check`: two turns in a real browser, no JS errors (the
  stub chat in `dev/browser_server.clj` must answer the rewrite call).

## Docs

SPEC: new §10.4 (follow-up rewriting), §11 (`/ask` history,
`standalone_query`, `rewrite_failed`), §12 (conversation UI), §14
(`:rewrite` stage), §18.3 additions, §21 (mark done), test count;
`docs/decisions.md`. Then check `docs/howto/user.md`, the other HowTos,
README and quick-start for anything that no longer matches, English and
zh-TW in the same commit, `bb docs:check`.
