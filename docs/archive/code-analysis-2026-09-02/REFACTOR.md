---
title: "Refactoring Recommendations: memory"
date: "2026-09-02"
updated: "2026-09-02"
project: "wechat-agent-java"
type: "refactor-plan"
status: "active"
version: "1.0"
tags: ["wechat-agent-java", "refactor-plan", "module-analysis", "memory"]
changelog:
  - version: "1.0"
    date: "2026-09-02"
    changes: ["Recorded completed foundation work and bounded follow-up opportunities"]
related:
  - "[[REPORT]]"
---

# Memory Refactor

## Code Smells

| ID | Issue | Severity | Location | Description |
|---|---|---|---|---|
| D-001 | Fixed recent context | High | `ContextStore` usage | Immediate continuity depended on a fixed Redis window rather than durable history and a model budget |
| D-002 | Missing episodic layer | High | Memory model | Stable facts and active tasks could not represent a complete meaningful experience |
| D-003 | Tool history absent | High | `AgentLoop` | Tool calls and results were not retained with the conversation evidence |
| D-004 | Lexical-only retrieval | Medium | `MemoryRetrievalService` | Retrieval uses terms and n-grams; paraphrases with little lexical overlap can still be missed |

## Recommendations

| Issue | Recommendation | Priority | Impact | Effort |
|---|---|---|---|---|
| D-001 | Restore recent persistent events under turn and character budgets, with Redis fallback | High | High | Low |
| D-002 | Add provenance-aware episodic memories and include them in extraction, retrieval, backup, and forgetting | High | High | Medium |
| D-003 | Persist paired tool-call/tool-result events under the active user scope | High | High | Low |
| D-004 | Add an optional embedding index with deterministic lexical fallback and user-scoped filtering | Medium | Medium | High |

## Priority Matrix

```text
High Impact   | D-001 D-003 | D-002       |
Medium Impact |             |             | D-004
Low Impact    |             |             |
              +-------------+-------------+-------------
                Low Effort    Medium Effort High Effort
```

## Implementation Plan

### Completed Foundation

1. D-001 — Durable context budget and Redis fallback.
2. D-002 — Episodic entity, service, extraction, retrieval, backup, and source-based forgetting.
3. D-003 — User-scoped paired tool events in the append-only conversation table.

### Optional Evolution

1. D-004 — Introduce embeddings only after assembling a paraphrase-recall evaluation set.
2. Keep lexical ranking as a deterministic fallback and never query an unscoped vector collection.
3. Measure recall quality, prompt budget, and false-memory rate before enabling embeddings by default.

## Impact Analysis

| Risk | Probability | Severity | Mitigation |
|---|---|---|---|
| Production table missing | Medium | High | Apply the checked-in MySQL migration before production startup |
| Episode over-extraction | Medium | Medium | Confidence threshold, explicit-user-evidence rule, deduplication, and source provenance |
| Cross-user retrieval | Low | High | Repository scoping plus final ownership validation and regression tests |
| Prompt growth | Low | Medium | Per-section item and character budgets |

## Testing Strategy

1. Verify chronological context reconstruction and exclusion of internal tool events from conversational roles.
2. Verify episode timestamps, sources, deduplication, and update behavior.
3. Inject a foreign-user episode from a mocked dependency and assert the final renderer rejects it.
4. Run the complete Maven test suite after focused memory and agent tests.

## Referencias

- [[REPORT]] — Main technical analysis of the memory module
