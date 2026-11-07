package team.rainfall.finality.loader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import team.rainfall.finality.FinalityLogger;
import team.rainfall.finality.MetadataClassBuilder;
import team.rainfall.finality.PluginMetadata;
import team.rainfall.lumipatch.ClassPoolTransformer;
import team.rainfall.lumipatch.PatchSet;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.jar.JarOutputStream;

public final class Main {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LOADER_ID = "team.rainfall.finality.loader";
    private static final String LOADER_VERSION = "2.0.0";
    private static final String DISTRIBUTION_VERSION = "1.6.2-diagnostic";
    private static final int DEFAULT_D8_MIN_API = 21;

    private Main() { }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception e) {
            FinalityLogger.error("Finality Loader: " + e.getMessage(), e);
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        FinalityLogger.init(Paths.get(".").toAbsolutePath().normalize());
        boolean debug = Boolean.parseBoolean(System.getProperty("finality.debug", "false"));
        FinalityLogger.setDebug(debug);
        FinalityLogger.info("Finality Loader " + DISTRIBUTION_VERSION + " (Framework " + LOADER_VERSION + "); debug logging: " + (debug ? "enabled" : "disabled"));
        RunOptions options = parseArgs(args);
        if (options.values.containsKey("help")) {
            usage();
            return;
        }
        Path corePath = requiredPath(options.values, "core");
        Path listPath = requiredPath(options.values, "mods");
        FinalityLogger.debug("Java " + System.getProperty("java.version") + " from " + System.getProperty("java.home"));
        FinalityLogger.debug("Core jar: " + corePath.toAbsolutePath().normalize());
        FinalityLogger.debug("Plugin list: " + listPath.toAbsolutePath().normalize());
        if (!Files.isRegularFile(corePath)) throw new IOException("Game core jar does not exist: " + corePath);
        if (!Files.isRegularFile(listPath)) throw new IOException("Mod list does not exist: " + listPath);

        List<Plugin> plugins = loadPlugins(listPath);
        FinalityLogger.info("Loaded " + plugins.size() + " plugin(s).");
        Map<String, Plugin> byId = new LinkedHashMap<String, Plugin>();
        for (Plugin plugin : plugins) {
            if ("core".equals(plugin.id)) throw new IOException("Plugin id 'core' is reserved");
            if (byId.put(plugin.id, plugin) != null) throw new IOException("Duplicate plugin id: " + plugin.id);
        }
        plugins = resolveDependenciesAndConflicts(plugins, byId);
        byId = new LinkedHashMap<String, Plugin>();
        for (Plugin plugin : plugins) byId.put(plugin.id, plugin);
        validatePlugins(plugins, byId);
        List<Plugin> ordered = dependencyOrder(plugins, byId);
        List<PluginMetadata> metadata = new ArrayList<PluginMetadata>();
        metadata.add(new PluginMetadata("team.rainfall.finality.loader", "Finality Loader", "2.0.0", null));
        for (Plugin plugin : ordered) metadata.add(new PluginMetadata(plugin.id, plugin.name, plugin.version, plugin.mainClass));

        String mainClass;
        try (JarFile jar = new JarFile(corePath.toFile())) {
            Manifest manifest = jar.getManifest();
            if (manifest == null) throw new IOException("Game core has no MANIFEST.MF");
            mainClass = manifest.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
        }
        if (mainClass == null || mainClass.trim().isEmpty()) throw new IOException("Game core manifest does not declare Main-Class");

        if (options.buildForAndroid) {
            buildForAndroid(corePath, listPath, ordered, metadata, options);
            return;
        }

        List<URL> urls = new ArrayList<URL>();
        List<java.util.jar.JarFile> pluginJars = new ArrayList<java.util.jar.JarFile>();
        List<Path> extractedLibraries = new ArrayList<Path>();
        try (JarFile coreJar = new JarFile(corePath.toFile())) {
            List<ClassPoolTransformer.JarPatch> patches = new ArrayList<ClassPoolTransformer.JarPatch>();
            for (Plugin plugin : ordered) {
                java.util.jar.JarFile jar = new java.util.jar.JarFile(plugin.jar.toFile());
                pluginJars.add(jar);
                patches.add(new ClassPoolTransformer.JarPatch(jar, plugin.mixins));
                urls.add(plugin.jar.toUri().toURL());
                for (Path library : plugin.libraries) {
                    urls.add(library.toUri().toURL());
                    extractedLibraries.add(library);
                }
            }
            urls.add(corePath.toUri().toURL());
            Map<String, byte[]> classPool = new ClassPoolTransformer().transform(coreJar, patches);
            MetadataClassBuilder.addTo(classPool, metadata, true, modDirectories(listPath));
            try (PluginClassLoader loader = new PluginClassLoader(urls.toArray(new URL[urls.size()]), Main.class.getClassLoader(), classPool)) {
            Class.forName(MetadataClassBuilder.PLUGINS_COLLECTION, true, loader);
            Class.forName(MetadataClassBuilder.MOD_LIST, true, loader);
            FinalityLogger.info("Generated plugin and mod metadata classes.");
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> entry = Class.forName(mainClass, true, loader);
            Method main = entry.getMethod("main", String[].class);
            try {
                main.invoke(null, (Object) gameArgs(args));
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw e;
            }
            }
        } finally {
            for (java.util.jar.JarFile jar : pluginJars) jar.close();
            for (Path library : extractedLibraries) Files.deleteIfExists(library);
        }
    }

    private static List<Plugin> loadPlugins(Path listFile) throws IOException {
        JsonNode root = JSON.readTree(listFile.toFile());
        JsonNode mods = root.isArray() ? root : root.get("mods");
        if (mods == null || !mods.isArray()) throw new IOException("Mod list must be a JSON array or an object with a 'mods' array");
        List<Plugin> result = new ArrayList<Plugin>();
        Path base = listFile.toAbsolutePath().getParent();
        for (JsonNode item : mods) {
            if (!item.isTextual()) throw new IOException("Each mod list entry must be a directory path");
            Path modDir = base.resolve(item.asText()).normalize();
            if (!Files.isDirectory(modDir)) throw new IOException("Mod directory does not exist: " + modDir);
            Path pluginDir = modDir.resolve("plugins");
            if (!Files.exists(pluginDir)) continue;
            if (!Files.isDirectory(pluginDir)) throw new IOException("Expected plugins directory: " + pluginDir);
            try (java.util.stream.Stream<Path> files = Files.list(pluginDir)) {
                java.util.Iterator<Path> iter = files.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".jar"))
                        .sorted().iterator();
                while (iter.hasNext()) result.add(readPlugin(iter.next()));
            }
        }
        return result;
    }

    private static Plugin readPlugin(Path file) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry manifestEntry = zip.getEntry("manifest.json");
            if (manifestEntry == null) throw new IOException(file + " is missing manifest.json");
            JsonNode manifest = JSON.readTree(zip.getInputStream(manifestEntry));
            String name = requiredText(manifest, "name", file);
            String id = requiredText(manifest, "id", file);
            String version = requiredText(manifest, "version", file);
            String description = optionalText(manifest, "description");
            if (description == null) description = "";
            String mainClass = optionalText(manifest, "main-class");
            String strategy = optionalText(manifest, "strategy");
            if (strategy == null || strategy.trim().isEmpty()) strategy = "eject";
            if (!isStrategy(strategy)) throw new IOException(file + " has invalid strategy: " + strategy);
            Path extracted = file;
            List<Path> libraries = Collections.emptyList();
            try { SemVer.parse(version); } catch (SemVer.IOExceptionVersion e) { throw new IOException(file + " has invalid SemVer version: " + version, e); }
            List<Requirement> dependencies = requirements(manifest, "dependencies", "depends", file);
            List<Requirement> conflicts = requirements(manifest, "conflicts", "conflict", file);
            List<String> mixins = PatchSet.read(zip).getMixins();
            return new Plugin(name, id, version, description, mainClass, extracted, libraries, dependencies, conflicts, mixins, strategy);
        }
    }

    private static String requiredText(JsonNode node, String key, Path file) throws IOException {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual() || value.asText().trim().isEmpty()) throw new IOException(file + " manifest requires non-empty '" + key + "'");
        return value.asText();
    }

    private static String optionalText(JsonNode node, String key) { JsonNode value = node.get(key); return value != null && value.isTextual() ? value.asText() : null; }

    private static List<String> stringList(JsonNode node, String key, String alias, Path file) throws IOException {
        JsonNode values = node.has(key) ? node.get(key) : node.get(alias);
        if (values == null || values.isNull()) return Collections.emptyList();
        if (!values.isArray()) throw new IOException(file + " manifest field '" + key + "' must be an array of plugin ids");
        List<String> result = new ArrayList<String>();
        for (JsonNode value : values) {
            if (!value.isTextual() || value.asText().trim().isEmpty()) throw new IOException(file + " has an invalid id in '" + key + "'");
            result.add(value.asText());
        }
        return result;
    }

    private static List<Requirement> requirements(JsonNode node, String key, String alias, Path file) throws IOException {
        List<Requirement> result = new ArrayList<Requirement>();
        for (String value : stringList(node, key, alias, file)) result.add(Requirement.parse(value, file));
        return result;
    }

    private static List<String> modDirectories(Path listFile) throws IOException {
        JsonNode root = JSON.readTree(listFile.toFile());
        JsonNode mods = root.isArray() ? root : root.get("mods");
        List<String> result = new ArrayList<String>();
        Path base = listFile.toAbsolutePath().getParent();
        for (JsonNode item : mods) result.add(base.resolve(item.asText()).normalize().toString());
        return result;
    }

    private static void validatePlugins(List<Plugin> plugins, Map<String, Plugin> byId) throws IOException {
        for (Plugin plugin : plugins) {
            for (Requirement dependency : plugin.dependencies) {
                Plugin target = byId.get(dependency.id);
                if (target == null && LOADER_ID.equals(dependency.id)) {
                    if (dependency.constraint == null || dependency.constraint.matches(loaderVersion())) continue;
                }
                if (target == null || (dependency.constraint != null && !dependency.constraint.matches(target.semver))) throw new IOException("Plugin '" + plugin.id + "' has an unresolved dependency: '" + dependency + "'");
            }
        }
    }

    private static List<Plugin> resolveDependenciesAndConflicts(List<Plugin> plugins, Map<String, Plugin> byId) throws IOException {
        Set<String> ejected = new HashSet<String>();
        boolean changed;
        do {
            changed = false;
            Map<String, Plugin> active = new LinkedHashMap<String, Plugin>();
            for (Plugin plugin : plugins) if (!ejected.contains(plugin.id)) active.put(plugin.id, plugin);
            for (Plugin plugin : plugins) {
                if (ejected.contains(plugin.id)) continue;
                for (Requirement dependency : plugin.dependencies) {
                Plugin target = active.get(dependency.id);
                if (target == null && LOADER_ID.equals(dependency.id) && (dependency.constraint == null || dependency.constraint.matches(loaderVersion()))) continue;
                if (target != null && (dependency.constraint == null || dependency.constraint.matches(target.semver))) continue;
                String message = "Plugin '" + plugin.id + "' has an unresolved dependency: '" + dependency + "'";
                int before = ejected.size();
                applyStrategy(plugin, message, ejected);
                changed |= before != ejected.size();
                break;
                }
                if (ejected.contains(plugin.id)) continue;
                for (Requirement conflict : plugin.conflicts) {
                Plugin target = active.get(conflict.id);
                if (target == null || (conflict.constraint != null && !conflict.constraint.matches(target.semver))) continue;
                String message = "Plugin '" + plugin.id + "' conflicts with installed plugin '" + target.id + "'";
                int before = ejected.size();
                applyStrategy(plugin, message, ejected);
                changed |= before != ejected.size();
                break;
                }
            }
        } while (changed);
        List<Plugin> result = new ArrayList<Plugin>();
        for (Plugin plugin : plugins) if (!ejected.contains(plugin.id)) result.add(plugin);
        return result;
    }

    private static void applyStrategy(Plugin plugin, String message, Set<String> ejected) throws IOException {
        if ("crash".equals(plugin.strategy)) throw new IOException(message);
        if ("eject".equals(plugin.strategy)) { ejected.add(plugin.id); FinalityLogger.warn(message + "; ejecting " + plugin.id); }
        else FinalityLogger.warn(message);
    }

    private static boolean isStrategy(String strategy) {
        return "warn-only".equals(strategy) || "crash".equals(strategy) || "eject".equals(strategy);
    }

    private static SemVer loaderVersion() throws IOException {
        try { return SemVer.parse(LOADER_VERSION); }
        catch (SemVer.IOExceptionVersion e) { throw new IOException("Invalid built-in loader version", e); }
    }

    private static List<Plugin> dependencyOrder(List<Plugin> plugins, Map<String, Plugin> byId) throws IOException {
        List<Plugin> ordered = new ArrayList<Plugin>();
        Set<String> visiting = new HashSet<String>();
        Set<String> visited = new HashSet<String>();
        for (Plugin plugin : plugins) visit(plugin, byId, visiting, visited, ordered);
        return ordered;
    }

    private static void visit(Plugin plugin, Map<String, Plugin> byId, Set<String> visiting, Set<String> visited, List<Plugin> ordered) throws IOException {
        if (visited.contains(plugin.id)) return;
        if (!visiting.add(plugin.id)) throw new IOException("Plugin dependency cycle includes '" + plugin.id + "'");
        for (Requirement dependency : plugin.dependencies) {
            if (LOADER_ID.equals(dependency.id)) continue;
            visit(byId.get(dependency.id), byId, visiting, visited, ordered);
        }
        visiting.remove(plugin.id);
        visited.add(plugin.id);
        ordered.add(plugin);
    }

    private static RunOptions parseArgs(String[] args) throws IOException {
        Map<String, String> options = new HashMap<String, String>();
        boolean buildForAndroid = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--help".equals(arg) || "-h".equals(arg)) { options.put("help", ""); continue; }
            if ("--build-for-android".equals(arg)) { buildForAndroid = true; continue; }
            String key;
            if ("--core".equals(arg) || "-c".equals(arg)) key = "core";
            else if ("--mods".equals(arg) || "-m".equals(arg)) key = "mods";
            else if ("--d8-desugaring-lib".equals(arg)) key = "d8DesugaringLib";
            else if ("--d8-min-api".equals(arg)) key = "d8MinApi";
            else if ("--".equals(arg)) { options.put("gameArgs", join(args, i + 1)); break; }
            else throw new IOException("Unknown option: " + arg);
            if (++i >= args.length) throw new IOException("Missing value for " + arg);
            options.put(key, args[i]);
        }
        return new RunOptions(options, buildForAndroid);
    }

    private static Path requiredPath(Map<String, String> options, String key) throws IOException {
        String value = options.get(key);
        if (value == null) throw new IOException("Missing required option --" + key);
        return Paths.get(value).toAbsolutePath().normalize();
    }

    private static String[] gameArgs(String[] args) {
        for (int i = 0; i < args.length; i++) if ("--".equals(args[i])) return java.util.Arrays.copyOfRange(args, i + 1, args.length);
        return new String[0];
    }

    private static String join(String[] values, int start) {
        StringBuilder result = new StringBuilder();
        for (int i = start; i < values.length; i++) { if (i > start) result.append(' '); result.append(values[i]); }
        return result.toString();
    }

    private static void buildForAndroid(Path corePath, Path listPath, List<Plugin> plugins,
                                        List<PluginMetadata> metadata, RunOptions options) throws Exception {
        Path output = corePath.resolveSibling(corePath.getFileName().toString().replaceFirst("\\.jar$", "") + "-android");
        Path d8 = findD8();
        if (!Files.isRegularFile(d8)) throw new IOException("d8 executable does not exist: " + d8);
        int minApi = parseMinApi(options.values.get("d8MinApi"));
        Path desugaringLib = resolveDesugaringLibrary(options.values.get("d8DesugaringLib"));

        Path merged = Files.createTempFile("finality-android-", ".jar");
        Path d8Output = Files.createTempDirectory("finality-d8-");
        try {
            try (JarFile core = new JarFile(corePath.toFile())) {
                List<ClassPoolTransformer.JarPatch> patches = new ArrayList<ClassPoolTransformer.JarPatch>();
                List<JarFile> pluginJars = new ArrayList<JarFile>();
                try {
                    for (Plugin plugin : plugins) {
                        JarFile jar = new JarFile(plugin.jar.toFile());
                        pluginJars.add(jar);
                        patches.add(new ClassPoolTransformer.JarPatch(jar, plugin.mixins));
                    }
                    Map<String, byte[]> classes = new ClassPoolTransformer().transform(core, patches);
                    MetadataClassBuilder.addTo(classes, metadata, true, modDirectories(listPath));
                    writeMergedJar(core, classes, merged);
                } finally {
                    for (JarFile jar : pluginJars) jar.close();
                }
            }

            List<String> command = new ArrayList<String>();
            command.add(d8.toString());
            command.add("--min-api");
            command.add(Integer.toString(minApi));
            if (desugaringLib != null) {
                command.add("--lib");
                command.add(desugaringLib.toString());
            }
            command.add("--output");
            command.add(d8Output.toString());
            command.add(merged.toString());
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            byte[] outputBytes = readAll(process.getInputStream());
            int exitCode = process.waitFor();
            if (exitCode != 0) throw new IOException("d8 failed with exit code " + exitCode + ": " + new String(outputBytes, java.nio.charset.StandardCharsets.UTF_8));
            Files.createDirectories(output);
            deleteDexFiles(output);
            int dexCount = 0;
            try (java.util.stream.Stream<Path> files = Files.list(d8Output)) {
                java.util.Iterator<Path> iterator = files.filter(path -> path.getFileName().toString().endsWith(".dex")).iterator();
                while (iterator.hasNext()) {
                    Path dex = iterator.next();
                    Files.copy(dex, output.resolve(dex.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    dexCount++;
                }
            }
            if (!Files.isRegularFile(output.resolve("classes.dex"))) throw new IOException("d8 did not produce classes.dex");
            FinalityLogger.info("Built Android dex output (" + dexCount + " dex file(s)) in " + output);
        } finally {
            Files.deleteIfExists(merged);
            deleteTree(d8Output);
        }
    }

    private static void writeMergedJar(JarFile core, Map<String, byte[]> classes, Path output) throws IOException {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(output))) {
            java.util.Enumeration<JarEntry> entries = core.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/") && entry.getName().endsWith(".SF")) continue;
                jar.putNextEntry(new JarEntry(entry.getName()));
                try (java.io.InputStream input = core.getInputStream(entry)) { copy(input, jar); }
                jar.closeEntry();
            }
            for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
    }

    private static void copy(java.io.InputStream input, java.io.OutputStream output) throws IOException {
        byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
    }

    private static byte[] readAll(java.io.InputStream input) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        copy(input, output); return output.toByteArray();
    }

    private static Path androidHome() {
        String home = System.getenv("ANDROID_HOME");
        if (home == null || home.trim().isEmpty()) home = System.getenv("ANDROID_SDK_ROOT");
        return home == null || home.trim().isEmpty() ? null : Paths.get(home);
    }

    private static Path findD8() throws IOException {
        Path home = androidHome();
        if (home == null) throw new IOException("No Android SDK configured; set ANDROID_HOME or ANDROID_SDK_ROOT");
        Path buildTools = home.resolve("build-tools");
        if (Files.isDirectory(buildTools)) {
            try (java.util.stream.Stream<Path> versions = Files.list(buildTools)) {
                List<Path> candidates = new ArrayList<Path>();
                versions.filter(Files::isDirectory).forEach(candidates::add);
                candidates.sort(NEWEST_VERSION_FIRST);
                for (Path version : candidates) {
                    Path unix = version.resolve("d8");
                    Path windows = version.resolve("d8.bat");
                    if (Files.isRegularFile(unix)) return unix;
                    if (Files.isRegularFile(windows)) return windows;
                }
            }
        }
        throw new IOException("No d8 executable found under " + buildTools);
    }

    private static int parseMinApi(String value) throws IOException {
        if (value == null || value.trim().isEmpty()) return DEFAULT_D8_MIN_API;
        try {
            int minApi = Integer.parseInt(value.trim());
            if (minApi <= 0) throw new NumberFormatException();
            return minApi;
        } catch (NumberFormatException e) {
            throw new IOException("Invalid --d8-min-api value: " + value);
        }
    }

    private static Path resolveDesugaringLibrary(String configured) throws IOException {
        if (configured != null) {
            Path path = Paths.get(configured).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) throw new IOException("Desugaring library does not exist: " + path);
            return path;
        }
        return findDesugaringLibrary();
    }

    /** Locates the Android desugaring library (or the platform android.jar) from the SDK, if available. */
    private static Path findDesugaringLibrary() {
        Path home = androidHome();
        if (home == null) return null;
        Path stubs = findInNewestVersionDirectory(home.resolve("build-tools"), "core-lambda-stubs.jar");
        if (stubs != null) return stubs;
        return findInNewestVersionDirectory(home.resolve("platforms"), "android.jar");
    }

    private static Path findInNewestVersionDirectory(Path root, String fileName) {
        if (!Files.isDirectory(root)) return null;
        try (java.util.stream.Stream<Path> children = Files.list(root)) {
            List<Path> candidates = new ArrayList<Path>();
            children.filter(Files::isDirectory).forEach(candidates::add);
            candidates.sort(NEWEST_VERSION_FIRST);
            for (Path candidate : candidates) {
                Path file = candidate.resolve(fileName);
                if (Files.isRegularFile(file)) return file;
            }
        } catch (IOException ignored) { }
        return null;
    }

    private static final java.util.Comparator<Path> NEWEST_VERSION_FIRST =
            (left, right) -> compareVersionNames(right.getFileName().toString(), left.getFileName().toString());

    private static int compareVersionNames(String left, String right) {
        int i = 0, j = 0;
        while (i < left.length() && j < right.length()) {
            char leftChar = left.charAt(i);
            char rightChar = right.charAt(j);
            if (Character.isDigit(leftChar) && Character.isDigit(rightChar)) {
                int leftEnd = i, rightEnd = j;
                while (leftEnd < left.length() && Character.isDigit(left.charAt(leftEnd))) leftEnd++;
                while (rightEnd < right.length() && Character.isDigit(right.charAt(rightEnd))) rightEnd++;
                long leftValue = Long.parseLong(left.substring(i, leftEnd));
                long rightValue = Long.parseLong(right.substring(j, rightEnd));
                int result = Long.compare(leftValue, rightValue);
                if (result != 0) return result;
                i = leftEnd;
                j = rightEnd;
            } else {
                if (leftChar != rightChar) return Character.compare(leftChar, rightChar);
                i++;
                j++;
            }
        }
        return Integer.compare(left.length() - i, right.length() - j);
    }

    private static void deleteDexFiles(Path directory) throws IOException {
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            java.util.Iterator<Path> iterator = files.filter(path -> path.getFileName().toString().endsWith(".dex")).iterator();
            while (iterator.hasNext()) Files.deleteIfExists(iterator.next());
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
            List<Path> paths = new ArrayList<Path>(); files.forEach(paths::add);
            Collections.sort(paths, java.util.Comparator.reverseOrder());
            for (Path path : paths) Files.deleteIfExists(path);
        }
    }

    private static void usage() {
        System.out.println("Usage: java -jar finality-loader.jar --core <game.jar> --mods <mod-list.json> [--build-for-android [--d8-desugaring-lib <jar>] [--d8-min-api <n>]] [-- <game args...>]");
        System.out.println("Mod list: [\"mods/example\"] or {\"mods\":[\"mods/example\"]}; each directory may contain plugins/*.jar.");
        System.out.println("Android: d8, the desugaring library, and multidex output are managed automatically.");
        System.out.println("Android output: <core>-android next to the game jar; default --d8-min-api is 21.");
    }

    private static final class RunOptions {
        final Map<String, String> values;
        final boolean buildForAndroid;
        RunOptions(Map<String, String> values, boolean buildForAndroid) {
            this.values = values; this.buildForAndroid = buildForAndroid;
        }
    }

    private static final class Plugin {
        final String name, id, version, description, mainClass;
        final Path jar;
        final List<Path> libraries;
        final List<Requirement> dependencies, conflicts;
        final String strategy;
        final List<String> mixins;
        final SemVer semver;
        Plugin(String name, String id, String version, String description, String mainClass, Path jar, List<Path> libraries, List<Requirement> dependencies, List<Requirement> conflicts, List<String> mixins, String strategy) throws IOException {
            this.name = name; this.id = id; this.version = version; this.description = description; this.mainClass = mainClass;
            this.jar = jar; this.libraries = libraries; this.dependencies = dependencies; this.conflicts = conflicts; this.mixins = mixins; this.strategy = strategy;
            try { this.semver = SemVer.parse(version); } catch (SemVer.IOExceptionVersion e) { throw new IOException(e); }
        }
    }

    private static final class Requirement {
        final String id;
        final VersionConstraint constraint;
        private Requirement(String id, VersionConstraint constraint) { this.id = id; this.constraint = constraint; }
        static Requirement parse(String value, Path file) throws IOException {
            java.util.regex.Matcher match = java.util.regex.Pattern.compile("^([A-Za-z0-9_.-]+)\\s*(>=|<=|>|<|=)?\\s*(.*)$").matcher(value.trim());
            if (!match.matches()) throw new IOException(file + " has invalid dependency/conflict: " + value);
            String version = match.group(3).trim();
            if (version.isEmpty()) return new Requirement(match.group(1), null);
            try { return new Requirement(match.group(1), VersionConstraint.parse((match.group(2) == null ? "=" : match.group(2)) + version)); }
            catch (SemVer.IOExceptionVersion e) { throw new IOException(file + " has invalid version requirement: " + value, e); }
        }
        @Override public String toString() { return id + (constraint == null ? "" : constraint.toString()); }
    }

    private static final class PluginClassLoader extends URLClassLoader {
        private final Map<String, byte[]> classPool;
        PluginClassLoader(URL[] urls, ClassLoader parent, Map<String, byte[]> classPool) { super(urls, parent); this.classPool = classPool; }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.") || name.startsWith("sun.")) {
                        loaded = getParent().loadClass(name);
                    } else {
                        byte[] bytes = classPool.get(name.replace('.', '/') + ".class");
                        try { loaded = bytes == null ? findClass(name) : defineClass(name, bytes, 0, bytes.length); }
                        catch (ClassNotFoundException ignored) { loaded = getParent().loadClass(name); }
                    }
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }
    }
}
