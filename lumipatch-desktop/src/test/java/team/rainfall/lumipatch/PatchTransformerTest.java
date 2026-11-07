package team.rainfall.lumipatch;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.FieldNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PatchTransformerTest {
    @Test
    void overwriteAndUnmarkedMethodsAreApplied() throws Exception {
        byte[] transformed = new PatchTransformer().apply(bytes(Target.class), bytes(OverwriteMixin.class));
        ClassNode node = read(transformed);
        Set<String> names = new HashSet<String>();
        for (MethodNode method : node.methods) names.add(method.name + method.desc);
        assertTrue(names.contains("greet()Ljava/lang/String;"));
        assertTrue(names.contains("helper()Ljava/lang/String;"));
    }

    @Test
    void unmarkedMethodsOverwriteMatchingMethodsAndKeepNewNames() throws Exception {
        ClassNode node = read(new PatchTransformer().apply(bytes(Target.class), bytes(DefaultOverwriteMixin.class)));
        int greetMethods = 0;
        boolean helperPresent = false;
        boolean renamedGreet = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("greet") && method.desc.equals("()Ljava/lang/String;")) greetMethods++;
            if (method.name.equals("helper") && method.desc.equals("()Ljava/lang/String;")) helperPresent = true;
            if (method.name.startsWith("lumipatch$greet")) renamedGreet = true;
        }
        assertEquals(1, greetMethods);
        assertTrue(helperPresent);
        assertTrue(!renamedGreet);
    }

    @Test
    void copiedMixinSelfTypesAreRemappedToTargetClass() throws Exception {
        byte[] transformed = new PatchTransformer().apply(bytes(SelfTypeTarget.class), bytes(SelfTypeMixin.class));
        ClassNode node = read(transformed);
        MethodNode method = null;
        for (MethodNode candidate : node.methods) if (candidate.name.equals("getSelf")) method = candidate;
        assertTrue(method != null);
        assertEquals("(Lteam/rainfall/lumipatch/PatchTransformerTest$SelfTypeTarget;)Lteam/rainfall/lumipatch/PatchTransformerTest$SelfTypeTarget;", method.desc);
        for (org.objectweb.asm.tree.AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof org.objectweb.asm.tree.TypeInsnNode && instruction.getOpcode() == Opcodes.CHECKCAST) {
                assertEquals("team/rainfall/lumipatch/PatchTransformerTest$SelfTypeTarget", ((org.objectweb.asm.tree.TypeInsnNode) instruction).desc);
            }
            if (instruction instanceof org.objectweb.asm.tree.FieldInsnNode) {
                assertEquals("team/rainfall/lumipatch/PatchTransformerTest$SelfTypeTarget", ((org.objectweb.asm.tree.FieldInsnNode) instruction).owner);
            }
        }
    }

    @Test
    void deprecatedAutoRenameStillUsesDefaultMethodBehavior() throws Exception {
        ClassNode node = read(new PatchTransformer().apply(bytes(Target.class), bytes(AutoRenameMixin.class)));
        int greetMethods = 0;
        for (MethodNode method : node.methods) if (method.name.equals("greet") && method.desc.equals("()Ljava/lang/String;")) greetMethods++;
        assertEquals(1, greetMethods);
    }

    @Test
    void deprecatedAutoRenameDoesNotChangeOverwriteBehavior() throws Exception {
        PatchTransformer transformer = new PatchTransformer();
        byte[] transformed = transformer.apply(bytes(Target.class), bytes(AutoRenameMixin.class));
        transformed = transformer.apply(transformed, bytes(SecondAutoRenameMixin.class));
        ClassNode node = read(transformed);
        int greetMethods = 0;
        int renamedMethods = 0;
        for (MethodNode method : node.methods) {
            if (method.name.equals("greet") && method.desc.equals("()Ljava/lang/String;")) greetMethods++;
            if (method.name.startsWith("lumipatch$greet")) renamedMethods++;
        }
        assertEquals(1, greetMethods);
        assertEquals(0, renamedMethods);
    }

    @Test
    void twoDefaultMixinsWithSameMethodNameUseOneReplacement() throws Exception {
        PatchTransformer transformer = new PatchTransformer();
        byte[] transformed = transformer.apply(bytes(Target.class), bytes(DefaultOverwriteMixin.class));
        transformed = transformer.apply(transformed, bytes(SecondDefaultMixin.class));
        ClassNode node = read(transformed);
        int helperMethods = 0;
        for (MethodNode method : node.methods) {
            if (method.name.equals("helper") && method.desc.equals("()Ljava/lang/String;")) helperMethods++;
        }
        assertEquals(1, helperMethods);
    }

    @Test
    void mixinFieldsMissingFromTargetAreAdded() throws Exception {
        ClassNode node = read(new PatchTransformer().apply(bytes(Target.class), bytes(FieldMixin.class)));
        boolean found = false;
        for (FieldNode field : node.fields) {
            if (field.name.equals("addedByMixin") && field.desc.equals("Ljava/lang/String;")) found = true;
        }
        assertTrue(found);
    }

    @Test
    void injectAtHeadAddsCallbackCall() throws Exception {
        byte[] transformed = new PatchTransformer().apply(bytes(Target.class), bytes(InjectMixin.class));
        ClassNode node = read(transformed);
        MethodNode method = null;
        for (MethodNode candidate : node.methods) if (candidate.name.equals("run")) method = candidate;
        boolean hasCallback = false;
        for (org.objectweb.asm.tree.AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
            if (instruction.getOpcode() == Opcodes.INVOKEVIRTUAL) hasCallback = true;
        }
        assertTrue(hasCallback);
    }

    @Test
    void twoMixinsInjectingSameMethodCanShareCallbackName() throws Exception {
        PatchTransformer transformer = new PatchTransformer();
        byte[] transformed = transformer.apply(bytes(Target.class), bytes(InjectMixin.class));
        transformed = transformer.apply(transformed, bytes(SecondInjectMixin.class));
        ClassNode node = read(transformed);
        int callbacks = 0;
        Set<String> names = new HashSet<String>();
        for (MethodNode method : node.methods) {
            if (method.name.startsWith("lumipatch$callback")) {
                callbacks++;
                names.add(method.name + method.desc);
            }
        }
        assertEquals(2, callbacks);
        assertEquals(2, names.size());
    }

    @Test
    void patchJsonReadsMixinList() throws Exception {
        Path file = Files.createTempFile("patch", ".jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("patch.json"));
            zip.write("{\"mixins\":[\"example.MyMixin\"]}".getBytes("UTF-8"));
            zip.closeEntry();
        }
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file.toFile())) {
            assertEquals(Arrays.asList("example.MyMixin"), PatchSet.read(zip).getMixins());
        }
    }

    @Test
    void configuredClassWithoutMixinAnnotationIsSkipped() throws Exception {
        Path corePath = Files.createTempFile("core", ".jar");
        Path pluginPath = Files.createTempFile("plugin", ".jar");
        try {
            writeClassJar(corePath, null, null);
            writeClassJar(pluginPath, "example/NotAMixin.class", bytes(NotAMixin.class));
            try (java.util.jar.JarFile core = new java.util.jar.JarFile(corePath.toFile());
                 java.util.jar.JarFile plugin = new java.util.jar.JarFile(pluginPath.toFile())) {
                java.util.Map<String, byte[]> transformed = new ClassPoolTransformer().transform(core,
                        Arrays.asList(new ClassPoolTransformer.JarPatch(plugin, Arrays.asList("example.NotAMixin"))));
                assertTrue(transformed.containsKey("example/NotAMixin.class"));
            }
        } finally {
            Files.deleteIfExists(corePath);
            Files.deleteIfExists(pluginPath);
        }
    }

    private static void writeClassJar(Path path, String entryName, byte[] bytes) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            if (entryName != null) {
                zip.putNextEntry(new ZipEntry(entryName));
                zip.write(bytes);
                zip.closeEntry();
            }
        }
    }

    @Test
    void proxyAddsTargetMethodAndFacade() throws Exception {
        PatchTransformer transformer = new PatchTransformer();
        byte[] target = transformer.apply(bytes(Target.class), bytes(ProxyMixin.class));
        ClassNode targetNode = read(target);
        boolean targetProxy = false;
        for (MethodNode method : targetNode.methods) if (method.name.equals("getSecret")) targetProxy = true;
        assertTrue(targetProxy);
        ClassNode facade = read(transformer.transformMixinFacade(bytes(ProxyMixin.class), target));
        boolean facadeCall = false;
        for (MethodNode method : facade.methods) if (method.name.equals("getSecret")) {
            for (org.objectweb.asm.tree.AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) if (instruction.getOpcode() == Opcodes.INVOKESTATIC) facadeCall = true;
        }
        assertTrue(facadeCall);
    }

    @Test
    void callbackInfoEnforcesCancellationAndExposesLocals() {
        CallbackInfo info = new CallbackInfo("run", true, new Object[] { "before" }, new int[] { 1 }, new String[] { "java.lang.String" });
        assertEquals("before", info.getLocal(0));
        assertEquals(1, info.getLocalSize(0));
        assertEquals("java.lang.String", info.getLocalType(0));
        info.setLocal(0, "after");
        info.cancel();
        assertTrue(info.isCancelled());
        assertEquals("after", info.getLocal(0));
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] bytes(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        java.io.InputStream input = PatchTransformerTest.class.getResourceAsStream(resource);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096]; int count;
        while ((count = input.read(buffer)) >= 0) if (count > 0) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    public static class Target {
        private String secret = "secret";
        public String greet() { return "target"; }
        public void run() { }
        public String lumipatch$helper() { return "existing"; }
    }

    public static class NotAMixin { }

    public static class SelfTypeTarget {
        public String value;
        public SelfTypeTarget getSelf(SelfTypeTarget self) { return self; }
    }

    @Mixin(target = "team.rainfall.lumipatch.PatchTransformerTest$SelfTypeTarget")
    public static class SelfTypeMixin {
        public String value;
        public SelfTypeMixin getSelf(SelfTypeMixin self) {
            SelfTypeMixin copy = (SelfTypeMixin) self;
            copy.value = "mapped";
            return copy;
        }
    }

    @Mixin(target = "team.rainfall.lumipatch.PatchTransformerTest$Target")
    public static class OverwriteMixin {
        @Overwrite
        public String greet() { return "mixin"; }
        public String helper() { return "helper"; }
    }

    @Mixin(target = "team.rainfall.lumipatch.PatchTransformerTest$Target")
    public static class FieldMixin {
        public String addedByMixin;
    }

    @Mixin(target = "team.rainfall.lumipatch.PatchTransformerTest$Target")
    public static class InjectMixin {
        @Inject(method = "run", at = @At(value = "HEAD"), cancellable = true, dumpLocals = true)
        public void callback(CallbackInfo info) { }
    }

    @Mixin(target = "team.rainfall.lumipatch.PatchTransformerTest$Target")
    public static class SecondInjectMixin {
        @Inject(method = "run", at = @At(value = "HEAD"))
        public void callback(CallbackInfo info) { }
    }

    @Mixin(target = "team.rainfall.lumipatch.PatchTransformerTest$Target")
    public static class ProxyMixin {
        @Proxy
        public static String getSecret(Target target) { return target.secret; }
    }

    @Test
    void cancellableReturnValueAndDeprecatedAutoRenameAreSupported() throws Exception {
        CallbackInfo info = new CallbackInfo("value", true);
        info.cancel("replacement");
        assertEquals("replacement", info.getReturnValue());
        byte[] transformed = new PatchTransformer().apply(bytes(Target.class), bytes(CollisionMixin.class));
        ClassNode node = read(transformed);
        boolean helperPresent = false;
        for (MethodNode method : node.methods) if (method.name.equals("helper") && method.desc.equals("()Ljava/lang/String;")) helperPresent = true;
        assertTrue(helperPresent);
    }

    @Mixin(target = "team.rainfall.lumipatch.PatchTransformerTest$Target", autoRename = true)
    public static class CollisionMixin {
        public String helper() { return "collision"; }
    }

    @Mixin(target = "team.rainfall.lumipatch.PatchTransformerTest$Target", autoRename = false)
    public static class DefaultOverwriteMixin {
        public String greet() { return "replacement"; }
        public String helper() { return "added"; }
    }

    @Mixin(target = "team.rainfall.lumipatch.PatchTransformerTest$Target", autoRename = false)
    public static class SecondDefaultMixin {
        public String helper() { return "second"; }
    }

    @Mixin(target = "team.rainfall.lumipatch.PatchTransformerTest$Target", autoRename = true)
    public static class AutoRenameMixin {
        public String greet() { return "renamed"; }
    }

    @Mixin(target = "team.rainfall.lumipatch.PatchTransformerTest$Target", autoRename = true)
    public static class SecondAutoRenameMixin {
        public String greet() { return "renamed-second"; }
    }
}
