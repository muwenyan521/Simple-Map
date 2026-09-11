# Simple Map fork development

This repository is an independent maintenance fork of Simple Map. The upstream project is
paused; this fork owns its own issue triage, release schedule, and technical direction while
retaining the upstream Git history and MIT license.

## Toolchain

- Minecraft `1.21.1`
- NeoForge `21.1.65`
- Java `21`
- Gradle Wrapper `9.2.1`

Use the checked-in wrapper rather than a globally installed Gradle:

```bash
./gradlew build
```

On Windows:

```powershell
.\gradlew.bat build
```

The development run configurations are:

```bash
./gradlew runClient
./gradlew runServer
```

The built mod jar is written to `../mods/` by the Gradle configuration. Local Minecraft
run data is kept under `run/` and is intentionally ignored by Git.

## Verification commands

The normal build compiles production and standalone architecture-check sources:

```bash
./gradlew build
./gradlew test
```

The repository also contains dependency-light checks registered as JavaExec tasks. Useful
focused checks include:

```bash
./gradlew m2m4CoreCheck
./gradlew architectureStackCheck
./gradlew caveProjectionSemanticsCheck
./gradlew retainedViewportCompositionCheck
./gradlew framePacingHardGateCheck
```

`architectureAllCheck` is the aggregate Cave and renderer gate. It is intentionally run as
an informational baseline in CI while the fork closes the existing failure below; it must
not be described as green until the failing check is fixed and rerun.

## Known baseline failure

As of the fork-maintenance baseline (`564eb2ec950d5db80b4534c9d0d794ef050ee838`),

```bash
./gradlew architectureAllCheck
```

fails at `caveContiguousPublicationCheck` with:

```text
fullscreen planning is not bounded/deterministic and page-local
```

The failure is in the source-architecture contract in
`src/test/java/com/velorise/simplemap/client/cave/CaveContiguousPublicationCheck.java`.
The implementation already contains the bounded fixed-region window, scanline cursor,
page-local commit, and completion-eligibility paths that the check is intended to guard.
The next Cave task is to determine whether the contract is stale or the implementation is
missing a required invariant, then make the smallest behavior-preserving correction. Do not
remove the check or hide its exit status.

## Change and release rules

1. Keep Surface behavior and Cave behavior separately identifiable in commits and release
   notes.
2. Do not silently change cache formats or invalidate existing map data; document migrations
   before changing persistence code.
3. Keep the inherited version until a real fork release is validated. Fork releases should
   use an unambiguous fork version (for example, `2.1.1-fork.1`) rather than reusing an
   upstream release tag.
4. A release needs a successful `build`, focused architecture checks, and real-client smoke
   testing. Cave features must be labelled experimental until their dimension and mode
   matrix has been exercised in a running client.
5. Preserve `LICENSE` and the upstream copyright notices. New code should identify the fork
   only where ownership or behavior actually differs.

## Local troubleshooting

- If Gradle reports a stale generated Minecraft artifact, run `./gradlew clean build`.
- If a client run leaves unexpected world data, remove only the relevant directory under
  `run/`; do not delete the user's normal Minecraft saves.
- Keep crash reports, debug captures, and performance traces outside tracked paths unless a
  specific issue needs a redacted fixture.
