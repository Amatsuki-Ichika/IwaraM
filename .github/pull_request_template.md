## Summary

<!-- What does this change do, and why? One paragraph. Reference the issue: Closes #12 / Refs #12 -->

## Changes

**Interface changes** — commands, Gradle tasks, settings keys, public APIs, Room schema, file
layout, signing or version handling. Write `None` when there are none.

**Behavior changes** — what a user, a screen, a log or a test can observe. Include default values,
timing, ordering and error text. Write `None` when there are none. Documentation and `AGENTS.md`
changes are behavior changes for the readers they guide.

<State only behavior the diff guarantees and the tests cover.>

## Testing

**Test design** — how the tests cover each item in Changes, and why that coverage is complete:

- Behavior change → scenario × before × after × covering test
- Refactor with no behavior change → how "unchanged" is shown (same assertions, byte-identical output)
- UI change → which screen and states were checked manually, and what to look at
- Documentation only → `node scripts/check.mjs` and what it checks

**Test entries** — one per command actually run, with the result:

| Command | Result | Where |
| --- | --- | --- |
| `./gradlew :app:testDebugUnitTest --tests '*VideoSorterTest'` | pass | local |
| `node scripts/check.mjs` | pass | local |

<details><summary>Proof</summary>

```
<paste the relevant output, screenshot path or log excerpt>
```

</details>

## AI Usage

<!-- Who made the decisions in each stage. Levels: hint (human did it alone) / assist (human led) /
pair (human steered, agent implemented) / copilot (agent did it, human reviewed) / auto (agent did it
autonomously). Grade by who supplied the adopted structure, not by who typed the code. -->

| Stage | Level | Decision record |
| --- | --- | --- |
| breakdown 问题拆解 | <level> | <who defined the problem and scope> |
| design 方案设计 | <level> | <who supplied the adopted structure> |
| testing 验收测试 | <level> | <who set the cases and checked the result> |
| implementation 技术实现 | <level> | <who wrote the key parts> |
| review 评审处理 | <level> | <who decided each review item> |

## Checklist

- [ ] `./gradlew verify` passes locally
- [ ] `node scripts/check.mjs` passes
- [ ] New or changed pure logic is covered by a unit test that would fail before the change
- [ ] User-visible changes are in `CHANGELOG.md` under `[Unreleased]`
- [ ] A non-obvious decision has a record under `docs/decisions/`
- [ ] No secrets, SDK paths, keystore material or build output are included
