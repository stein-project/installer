# Contributing to Stein Installer

Read the [Stein contributing guide](https://github.com/Stein-Project/.github/blob/main/CONTRIBUTING.md) first: it
covers the rules, code style, commits and the [CLA](https://github.com/Stein-Project/.github/blob/main/CLA.md) your first
pull request asks you to sign. This page is what is specific to the installer.

## Build and check

With [bridge](https://github.com/Stein-Project/bridge), [loader](https://github.com/Stein-Project/loader) and
[sdk](https://github.com/Stein-Project/sdk) cloned next to this repository (they are included as composite builds;
nothing to build by hand):

```sh
./gradlew spotlessApply                  # google-java-format --aosp on every Java file
./gradlew build -Pstein.targets=windows  # the installer and one runtime: enough for most changes
./gradlew build                          # every system, as a release builds it
```

Then install with the jar you built (`build/installer/SteinLoader-Installer.jar`) into a test game folder, not your
own `.minecraft`, and open the game from the launcher profile it wrote. Check both launcher formats when you touch
the profile: the official launcher and an older one.

## Rules specific to the installer

- **Java 8 for the installer itself.** `src/main/java` compiles with `--release 8`: no `var`, records, `List.of`,
  `String.repeat` or anything newer. Only the game and the Patcher run on Java 25.
- **Nothing from Mojang in the installer.** The game jar is read from the player's installation and patched on their
  machine; downloads come from Mojang's own servers, as the launcher does.
- **Player-facing text goes through `Messages`**, in English, Portuguese and Spanish.
- **Every download is checked.** A new download needs a sha256 or a signature the installer already knows, never one
  that comes from the same place as the file.
- **The Java runtime is pinned** in `gradle/runtimes.properties` (version, URL and sha256 for all four systems).
  Change all four together.

## Releases

The installer's release is the pack players download. Write `changelog/<version>.en_US.md` (and optionally `.pt_BR.md`) in the
[loader](https://github.com/Stein-Project/loader) repository, then push a `v<Install.VERSION>` tag here
(`v<version>-rc.1` for a test version, seen only by the Stein Client's test channel). `.github/workflows/release.yml`
builds the installer with the Bridge and the Loader and publishes it to the public distribution repository
[Stein-Project/stein-loader](https://github.com/Stein-Project/stein-loader): the release (installer, runtimes,
`SHA256SUMS.txt`) and `latest` in its `stein.json`. The old `x4vieer/stein-loader` is
frozen and gets nothing from here. Running it by hand is a dry run that publishes nothing. The installer reads `stein.json` (the mods index)
and the runtimes from that repository (`Urls.java`).

## The payload

`./gradlew build` builds the installer with the Bridge and the Loader as their builds make them (`../bridge` and
`../loader`, both open source and not obfuscated), LWJGL 3, the Patcher and the Bridge's patcher rules. A player's
stack trace names our classes as they are in the source.
