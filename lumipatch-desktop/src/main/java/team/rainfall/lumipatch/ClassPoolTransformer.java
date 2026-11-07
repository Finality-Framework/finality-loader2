package team.rainfall.lumipatch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.zip.ZipEntry;

public final class ClassPoolTransformer {
    public Map<String, byte[]> transform(JarFile core, List<JarPatch> plugins) throws IOException, LumiPatchException {
        Map<String, byte[]> pool = readClasses(core);
        URL[] urls = new URL[plugins.size() + 1];
        urls[0] = new java.io.File(core.getName()).toURI().toURL();
        for (int i = 0; i < plugins.size(); i++) urls[i + 1] = new java.io.File(plugins.get(i).jar.getName()).toURI().toURL();
        debug("ASM resolution classpath has " + urls.length + " jar(s):");
        for (URL url : urls) debug("  " + url);
        try (URLClassLoader classLoader = new URLClassLoader(urls, PatchTransformer.class.getClassLoader())) {
        debug("ASM resolution classloader: " + classLoader + ", parent=" + classLoader.getParent());
        return transform(core, plugins, pool, classLoader);
        }
    }

    private void debug(String message) {
        if (Boolean.getBoolean("finality.debug")) System.out.println("[DEBUG] " + message);
    }

    private Map<String, byte[]> transform(JarFile core, List<JarPatch> plugins, Map<String, byte[]> pool, ClassLoader classLoader) throws IOException, LumiPatchException {
        for (JarPatch plugin : plugins) {
            Map<String, byte[]> pluginClasses = readClasses(plugin.jar);
            for (Map.Entry<String, byte[]> entry : pluginClasses.entrySet()) pool.put(entry.getKey(), entry.getValue());
            for (String mixinName : plugin.mixins) {
                String mixinPath = mixinName.replace('.', '/') + ".class";
                byte[] mixin = pluginClasses.get(mixinPath);
                if (mixin == null) throw new LumiPatchException("Mixin class not found in plugin: " + mixinName);
                String target = targetName(mixin);
                if (target == null) continue;
                String targetPath = target.replace('.', '/') + ".class";
                byte[] current = pool.get(targetPath);
                if (current == null) throw new LumiPatchException("Mixin target class not found: " + target);
                pool.put(targetPath, new PatchTransformer(pool, classLoader).apply(current, mixin));
            }
            for (String mixinName : plugin.mixins) {
                String mixinPath = mixinName.replace('.', '/') + ".class";
                byte[] mixin = pluginClasses.get(mixinPath);
                String target = targetName(mixin);
                if (target == null) continue;
                String targetPath = target.replace('.', '/') + ".class";
                pluginClasses.put(mixinPath, new PatchTransformer(pool, classLoader).transformMixinFacade(mixin, pool.get(targetPath)));
                pool.put(mixinPath, pluginClasses.get(mixinPath));
            }
        }
        return pool;
    }

    private Map<String, byte[]> readClasses(JarFile jar) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<String, byte[]>();
        java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                try (java.io.InputStream input = jar.getInputStream(entry)) { result.put(entry.getName(), read(input)); }
            }
        }
        return result;
    }

    private byte[] read(java.io.InputStream input) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) >= 0) if (count > 0) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private String targetName(byte[] mixin) {
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(mixin).accept(node, org.objectweb.asm.ClassReader.SKIP_CODE | org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
        String descriptor = org.objectweb.asm.Type.getDescriptor(Mixin.class);
        if (node.visibleAnnotations != null) for (org.objectweb.asm.tree.AnnotationNode annotation : node.visibleAnnotations) if (descriptor.equals(annotation.desc)) {
            if (annotation.values != null) for (int i = 0; i + 1 < annotation.values.size(); i += 2) if ("target".equals(annotation.values.get(i))) return (String) annotation.values.get(i + 1);
        }
        if (node.invisibleAnnotations != null) for (org.objectweb.asm.tree.AnnotationNode annotation : node.invisibleAnnotations) if (descriptor.equals(annotation.desc)) {
            if (annotation.values != null) for (int i = 0; i + 1 < annotation.values.size(); i += 2) if ("target".equals(annotation.values.get(i))) return (String) annotation.values.get(i + 1);
        }
        System.err.println("[WARN] Skipping configured mixin class without @Mixin target: " + node.name);
        return null;
    }

    public static final class JarPatch {
        public final JarFile jar;
        public final List<String> mixins;
        public JarPatch(JarFile jar, List<String> mixins) { this.jar = jar; this.mixins = Collections.unmodifiableList(new ArrayList<String>(mixins)); }
    }
}
