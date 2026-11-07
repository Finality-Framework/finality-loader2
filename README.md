# Finality Loader

## Purpose

Finality Loader starts a game JAR with plugin classes loaded ahead of the game
core. It resolves mods, applies LumiPatch transformations, merges generated
metadata, and optionally builds an Android core with `d8`. It requires Java 8 or
newer.

## Build

From this directory, run:

```sh
./gradlew shadowJar
```

The runnable artifact is written to `build/libs/finality-loader-1.6.0.jar`.
Run it with:

```sh
java -jar build/libs/finality-loader-1.6.0.jar \
  --core /path/to/game.jar \
  --mods /path/to/mod-list.json \
  -- --fullscreen
```

To prebuild an Android core, add `--build-for-android` together with `--d8`
(or set `ANDROID_HOME` / `ANDROID_SDK_ROOT`) and `--android-output`.

## License

This project is licensed under the GNU Lesser General Public License v3.0
(LGPLv3). See [LICENSE](LICENSE).
