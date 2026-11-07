# Finality Loader

Finality Loader starts a game jar with plugin classes loaded ahead of the game core. It requires Java 8 or newer.

## Build and run

```sh
./gradlew shadowJar
java -jar build/libs/finality-loader-1.6.0.jar --core /path/to/game.jar --mods /path/to/mod-list.json -- --fullscreen
```

The mod list is a JSON array of mod directory paths, or an object with a `mods` array. Relative paths are resolved from the list file:

```json
["mods/example", "mods/another"]
```

Each mod directory may contain a `plugins/` directory. Every `*.jar` file there is a
standard plugin JAR containing compiled classes plus `manifest.json` and the required
`patch.json` at its root.

To prebuild an Android core, use `--build-for-android`. The loader resolves plugins,
runs LumiPatch, merges the transformed core and generated metadata, then invokes d8:

```sh
java -jar finality-loader.jar --core game.jar --mods mods.json \
  --build-for-android --d8 /path/to/d8 --android-output build/android \
  --d8-arg --min-api --d8-arg 26
```

`--d8` may be omitted when `ANDROID_HOME` or `ANDROID_SDK_ROOT` points to an SDK;
the newest build-tools d8 is selected. Repeat `--d8-arg` to pass additional d8
options. The output directory contains `classes.dex` and any additional dex files.

Example plugin manifest:

```json
{
  "name": "Example Plugin",
  "id": "example",
  "version": "1.0.0",
  "description": "An example plugin",
  "dependencies": [],
  "conflicts": []
}
```

`patch.json` declares Mixin classes:

```json
{
  "mixins": ["com.example.ExampleMixin"]
}
```

The desktop patch module provides `@Mixin(target = "...")`, `@Dummy`, `@Overwrite`, `@At`, and `@Inject`. Supported injection points are `HEAD`, `TAIL`, `BEFORE_INVOKE`, and `AFTER_INVOKE`. A method without `@Overwrite` replaces an existing target method with the same signature; when no matching target method exists, it is added with its declared name. Constructors are not added or replaced unless explicitly marked with `@Overwrite`. The deprecated `autoRename` annotation property is retained for source compatibility but has no effect. Inject callback methods are assigned unique `lumipatch$` names, including when multiple Mixins inject the same target method. Plugin classes are placed into the class pool before that plugin's Mixins are applied, so a later plugin targets the already-overridden class from an earlier plugin.

An `@Inject` method must match the target method's dispatch mode: static targets use static handlers, while virtual targets use virtual handlers. The handler accepts target arguments and a final `CallbackInfo`. `cancellable = true` enables `cancel()` and `cancel(Object value)`, causing the target method to return its default value or supplied value when cancelled. `dumpLocals = true` supplies an editable `Object[]` through `CallbackInfo#getLocals()` and `setLocal()`. `getLocalSize(index)` returns the LVT slot size, and `getLocalType(index)` returns the fully qualified Java type name recorded by the LVT.

`@Proxy` is for `public static` methods. The method is added to the target class and the Mixin class keeps a forwarding facade, allowing callers to access private target members through normal Mixin code, for example `getHp(Player player)`.

Plugin versions must be valid Semantic Versioning 2.0.0 values (`major.minor.patch`, with optional prerelease and build metadata). Dependencies and conflicts are arrays of plugin ids, optionally followed by a version constraint, such as `example>=1.3.0` or `example >=1.3.0 <2.0.0`. Multiple comparisons are combined with AND; a plain id matches any valid version. Missing or incompatible dependencies, matching conflicts, duplicate ids, and dependency cycles stop startup. Before launching the game, the loader generates and loads `team.rainfall.finality.generated.PluginsCollection` and `team.rainfall.finality.generated.ModList` with plugin and mod-list metadata. Colored console logs omit timestamps; timestamped log files are created under `./finality/logs/` with the loader startup time in each filename.
