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
(`v<version>-rc.1` for a test version, seen only by the test channel). `.github/workflows/release.yml` builds the
installer with the Bridge and the Loader and publishes it as a release of this repository: the installer, the runtimes,
`SHA256SUMS.txt` and `stein.json`, and writes `latest` into `stein.json` of the
[Stein registry](https://github.com/Stein-Project/registry) (the `REGISTRY_TOKEN` secret: Contents: write on the
registry). Running it by hand is a dry run that publishes nothing.

The Discord invite, the message on the Loader's menu (`news`) and the official mods (`sources`) are edited in
`stein.json` of the registry, which signs it; the Stein Client, the installer and the Loader read it there
(`Urls.java`). `stein.json` in this repository is a bridge for programs older than the registry: every release carries it
with `latest` filled in at `releases/latest/download/stein.json`, the installer falls back to it when the registry does
not answer, and a change to it on `main` is uploaded to the latest release by `.github/workflows/index.yml`.

Releases up to 0.1.33 went to [Stein-Project/stein-loader](https://github.com/Stein-Project/stein-loader), which is
frozen: launchers and installers up to that version read it and stay on 0.1.33.

## The payload

`./gradlew build` builds the installer with the Bridge and the Loader as their builds make them (`../bridge` and
`../loader`, both open source and not obfuscated), LWJGL 3, the Patcher and the Bridge's patcher rules. A player's
stack trace names our classes as they are in the source.
