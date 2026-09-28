# Changelog

All notable changes to LevinRAG are listed here. Versions follow
[Semantic Versioning](https://semver.org/); while the version is 0.x, any
release may change the HTTP API, corpus format, configuration or `bb`
commands. The planned releases are in the README, "Status and roadmap".

## [Unreleased]

## [0.1.0] - 2026-09-28

First tagged version: Phases 0–5 of the initial spec
(`docs/design/2026-09-22-initial-spec.md`), shared for early feedback.

- Ingest a directory of Markdown into one embedded Datalevin database:
  chunks, full-text index, vectors, permissions and the link graph are
  written per document in one transaction; `bb reindex` rebuilds the index.
- Retrieval: lexical (bigram analyzer for Chinese) + semantic + link-graph
  recall, RRF fusion, cross-encoder rerank, context expansion.
- Answers with validated `[n]` citations, in the web UI and through
  `POST /api/v1/ask`.
- Permissions are filtered inside retrieval; the security suite checks
  every document × unauthorized-user pair.
- Per-stage traces with rank and score for every candidate, shown in the
  Debug panel.
- `bb eval`: recall@k and MRR@10 on your own questions.
- Users, groups, sessions and API tokens; health endpoints; configurable
  model timeouts.
- Models through OpenAI-compatible APIs (vLLM, or llama.cpp on a laptop);
  tests run with stub models.

Known limits: not yet deployed in production; the ~100k-chunk target and
the latency goal are unmeasured; single-turn questions only; no SSO.

[Unreleased]: https://github.com/humorless/levinrag/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/humorless/levinrag/releases/tag/v0.1.0
