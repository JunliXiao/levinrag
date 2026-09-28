# Roadmap

**0.x, early preview.** Not yet deployed in production; the ~100k-chunk design target and the latency goal are unmeasured. The HTTP API, corpus format, configuration and `bb` commands may still change between 0.x releases. Changes are listed in [CHANGELOG.md](CHANGELOG.md).

Planned releases:

| Version | Content |
|---|---|
| 0.1.0 | Phases 0–5 of the initial spec: the current feature set |
| 0.2.0 | Follow-up questions in the web chat |
| 0.3.0 | A larger, harder eval corpus; rerank threshold calibration |
| 0.4.0 | Scale measured near 100k chunks; one real deployment |
| 1.0 | All of the criteria below are met |

1.0 means every claim in the [README](README.md) is backed by evidence, and the HTTP API (`/api/v1`), the corpus and permission format, the environment variables and the `bb` commands stop changing incompatibly. The index is not part of that promise: it is derived data, and an upgrade may require `bb reindex`. Criteria:

- someone other than the author has run the [quick start](docs/howto/quick-start.md) on their own corpus without help, and used LevinRAG on a real corpus for a while;
- one production deployment has been done;
- the ~100k-chunk target has been measured;
- `bb eval` distinguishes better variants from worse ones.

Details of the remaining work are in [SPEC.md §21](SPEC.md#21-remaining-work-and-next-steps).
