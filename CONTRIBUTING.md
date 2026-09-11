# Contributing to the Simple Map fork

This repository is an independently maintained fork. Contributions are welcome when they
preserve the MIT license, keep the change reviewable, and are validated against the affected
runtime path.

## Before opening a pull request

1. Read [DEVELOPMENT.md](DEVELOPMENT.md) and build with the checked-in Java 21 Gradle wrapper.
2. Keep each pull request focused on one behavior or maintenance concern.
3. Do not include local world saves, credentials, debug recordings, generated build output, or
   unredacted crash reports.
4. Preserve existing copyright notices and do not remove the inherited Git history.
5. Explain any cache-format, persistence, network-payload, or compatibility impact in the pull
   request description.

## Validation

Run the smallest check that exercises the change, then include the exact command and result in
the pull request. At minimum for code changes:

```bash
./gradlew build
```

For map-pipeline changes, run the relevant focused architecture checks listed in
[DEVELOPMENT.md](DEVELOPMENT.md). For UI or gameplay behavior, run `./gradlew runClient` and
describe the real-client scenario used to verify the change.

`architectureAllCheck` currently has a documented baseline failure at
`caveContiguousPublicationCheck`. Do not remove, bypass, or weaken that check to make a pull
request green. A contribution that changes its result must explain why.

## Scope and review

- Surface map stability and build/release reliability are the near-term priorities.
- Cave mapping is experimental. Cave changes need explicit mode, dimension, and cache impact
  notes because they affect asynchronous projection and rendering paths.
- Avoid unrelated reformatting and broad refactors in bug-fix pull requests.
- New behavior should include a focused regression check when the existing standalone check
  suite cannot cover it.

Maintainers may request narrower commits, real-client evidence, or a follow-up issue before
merging changes that affect persistence or Cave rendering.
