package team.rainfall.finality;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.List;

/** Creates the constant metadata classes before the game class loader starts. */
public final class MetadataClassBuilder {
    public static final String PLUGINS_COLLECTION = "team.rainfall.finality.generated.PluginsCollection";
    public static final String MOD_LIST = "team.rainfall.finality.generated.ModList";

    private MetadataClassBuilder() { }

    public static void addTo(java.util.Map<String, byte[]> pool, List<PluginMetadata> plugins,
                             boolean modListEnabled, List<String> mods) {
        pool.put(PLUGINS_COLLECTION.replace('.', '/') + ".class", pluginsClass(plugins));
        pool.put(MOD_LIST.replace('.', '/') + ".class", modListClass(modListEnabled, mods));
    }

    private static byte[] pluginsClass(List<PluginMetadata> plugins) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internal(PLUGINS_COLLECTION), null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "plugins", "Ljava/util/ArrayList;", null, null).visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        method.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList"); method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        method.visitFieldInsn(Opcodes.PUTSTATIC, internal(PLUGINS_COLLECTION), "plugins", "Ljava/util/ArrayList;");
        for (PluginMetadata plugin : plugins) {
            method.visitFieldInsn(Opcodes.GETSTATIC, internal(PLUGINS_COLLECTION), "plugins", "Ljava/util/ArrayList;");
            method.visitTypeInsn(Opcodes.NEW, "team/rainfall/finality/PluginMetadata"); method.visitInsn(Opcodes.DUP);
            push(method, plugin.id); push(method, plugin.name); push(method, plugin.version); push(method, plugin.mainClass);
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, "team/rainfall/finality/PluginMetadata", "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", false);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false); method.visitInsn(Opcodes.POP);
        }
        method.visitInsn(Opcodes.RETURN); method.visitMaxs(8, 0); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] modListClass(boolean enabled, List<String> mods) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internal(MOD_LIST), null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "modList", "Lteam/rainfall/finality/ModListMetadata;", null, null).visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        method.visitTypeInsn(Opcodes.NEW, "team/rainfall/finality/ModListMetadata"); method.visitInsn(Opcodes.DUP);
        method.visitInsn(enabled ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        method.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList"); method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        for (String mod : mods) { method.visitInsn(Opcodes.DUP); push(method, mod); method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false); method.visitInsn(Opcodes.POP); }
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "team/rainfall/finality/ModListMetadata", "<init>", "(ZLjava/util/ArrayList;)V", false);
        method.visitFieldInsn(Opcodes.PUTSTATIC, internal(MOD_LIST), "modList", "Lteam/rainfall/finality/ModListMetadata;");
        method.visitInsn(Opcodes.RETURN); method.visitMaxs(8, 0); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }

    private static void push(MethodVisitor method, String value) { if (value == null) method.visitInsn(Opcodes.ACONST_NULL); else method.visitLdcInsn(value); }
    private static String internal(String name) { return name.replace('.', '/'); }
}
