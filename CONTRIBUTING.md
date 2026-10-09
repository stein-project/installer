# Contributing to Stein Installer

Read the [Stein contributing guide](https://github.com/Stein-Project/.github/blob/main/CONTRIBUTING.md) first: it
covers the rules, code style and commits. This page is what is specific to the installer.

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
