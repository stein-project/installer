# Stein Installer

**Installs [Stein Bridge](https://github.com/Stein-Project/bridge) and [Stein Loader](https://github.com/Stein-Project/loader) on top of a player's own Minecraft 1.8.9.**

One `.jar` for every system. It patches the player's own game jar on their machine (nothing from Mojang is shipped),
writes a launcher profile that works in the official launcher and in older ones, downloads the official mods listed in
the Stein index, and installs the Java 25 the game runs on.

- **The jar opens on old Javas.** It is compiled for Java 8, because people playing 1.8.9 through an alternative
  launcher often have only that. The Java 25 for the game is a separate zip per system, downloaded while installing and
  checked against the sha256 written into the installer.
- **Windows without Java:** `SteinLoader-Installer-windows.zip` is the same installer as an app with Java inside.
- Most players never see it: the [Stein Client](https://github.com/Stein-Project/client) runs it for them.

> Stein is not an official Minecraft product. It is not approved by or associated with Mojang or Microsoft.

## Building

The installer ships the Bridge and the Loader built from source, so clone the repositories side by side:

```
stein/
├── bridge/      github.com/Stein-Project/bridge
├── loader/      github.com/Stein-Project/loader
├── sdk/         github.com/Stein-Project/sdk
└── installer/   this repository
```

You need a **JDK 25 or newer** (`JAVA_HOME`) and Minecraft 1.8.9 installed (or `./gradlew fetchMinecraft`
in the Bridge, then pass `-Pstein.mcDir=../bridge/build/mc`). Then, in `installer/`:

```sh
./gradlew build                          # every system: build/installer/
./gradlew build -Pstein.targets=windows  # only some systems (a quicker local build)
./gradlew spotlessApply                  # formats the Java code
```

Outputs in `build/installer/`:

| File | What it is |
|---|---|
| `SteinLoader-Installer.jar` | the installer, one for every system, with the Bridge, the Loader, LWJGL 3 and the Patcher inside |
| `runtime-<system>.zip` | the Java 25 of each system (`windows`, `linux`, `macos`, `macos-arm64`), built with jlink |
| `SteinLoader-Installer-windows.zip` | the Windows app with Java inside (only when built on Windows) |

The Java 25 comes from Amazon Corretto (OpenJDK, GPLv2 with the Classpath Exception, which allows shipping it, and with
the jmods jlink needs), pinned by version and sha256 in `gradle/runtimes.properties`. The runtime zips have no
timestamps, so the same Java gives the same zip and players do not download it again when only the installer changed.

## Layout

| Path | What it is |
|---|---|
| `src/main/java` | the installer (`dev.xavier.stein.installer`): the window, the command line, the installation |
| `test/` | manual checks (the installer window, the launcher profile a version writes) |
| `tools/MakeIco.java` | the Windows icon, from `src/main/resources/sl-256.png` |
| `gradle/runtimes.properties` | the pinned Corretto JDKs |

## Contributing

Bug reports, ideas and pull requests are welcome. Read the
[contributing guide](https://github.com/Stein-Project/.github/blob/main/CONTRIBUTING.md) first; your first pull
request asks you to sign the [CLA](https://github.com/Stein-Project/.github/blob/main/CLA.md). What is specific to the
installer is in [CONTRIBUTING.md](CONTRIBUTING.md).

## Licence

Stein Installer is licensed under the [Mozilla Public License 2.0](LICENSE). Third-party software and its licences are
in [NOTICE](NOTICE). The Stein name and logo are not covered by the licence: see the [trademark policy](https://github.com/Stein-Project/.github/blob/main/TRADEMARK.md).

Created by [Breno Xavier](https://github.com/brenoxavier).
