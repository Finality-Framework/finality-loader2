package team.rainfall.lumipatch;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.commons.MethodRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;

public final class PatchTransformer {
    private static final String MIXIN = Type.getDescriptor(Mixin.class);
    private static final String DUMMY = Type.getDescriptor(Dummy.class);
    private static final String OVERWRITE = Type.getDescriptor(Overwrite.class);
    private static final String INJECT = Type.getDescriptor(Inject.class);
    private static final String PROXY = Type.getDescriptor(Proxy.class);
    private static final String CALLBACK_INFO = Type.getInternalName(CallbackInfo.class);
    private static final String INIT = "<init>";
    private static final String CLINIT = "<clinit>";

    private final Map<String, byte[]> classPool;
    private final ClassLoader classLoader;

    public PatchTransformer() {
        this(Collections.<String, byte[]>emptyMap(), PatchTransformer.class.getClassLoader());
    }

    PatchTransformer(Map<String, byte[]> classPool, ClassLoader classLoader) {
        this.classPool = classPool;
        this.classLoader = classLoader;
    }

    public byte[] apply(byte[] targetBytes, byte[] mixinBytes) throws LumiPatchException {
        ClassNode target = read(targetBytes);
        ClassNode mixin = read(mixinBytes);
        String targetName = annotationString(mixin.visibleAnnotations, MIXIN, "target");
        if (targetName == null) throw new LumiPatchException("Mixin class " + mixin.name + " has no @Mixin target");
        if (targetName.replace('.', '/').equals(target.name)) {
            transform(target, mixin);
        }
        ClassWriter writer = new PoolClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        target.accept(writer);
        return writer.toByteArray();
    }

    public byte[] transformMixinFacade(byte[] mixinBytes, byte[] targetBytes) throws LumiPatchException {
        ClassNode mixin = read(mixinBytes);
        ClassNode target = read(targetBytes);
        for (MethodNode method : new ArrayList<MethodNode>(mixin.methods)) {
            if (hasAnnotation(method.visibleAnnotations, PROXY)) proxyFacade(target, method);
        }
        ClassWriter writer = new PoolClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        mixin.accept(writer);
        return writer.toByteArray();
    }

    private final class PoolClassWriter extends ClassWriter {
        PoolClassWriter(int flags) { super(flags); }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            debug("ASM common-superclass request: " + type1 + " vs " + type2 + " using " + classLoader);
            try {
                Class<?> first = Class.forName(type1.replace('/', '.'), false, classLoader);
                Class<?> second = Class.forName(type2.replace('/', '.'), false, classLoader);
                if (first.isAssignableFrom(second)) return type1;
                if (second.isAssignableFrom(first)) return type2;
                if (first.isInterface() || second.isInterface()) return "java/lang/Object";
                do {
                    first = first.getSuperclass();
                } while (!first.isAssignableFrom(second));
                return Type.getInternalName(first);
            } catch (ClassNotFoundException | LinkageError e) {
                debug("ASM classloader resolution failed for " + type1 + " / " + type2 + ": " + e);
                try {
                    String resolved = commonSuperClass(type1, type2);
                    debug("ASM class-pool fallback resolved " + type1 + " / " + type2 + " to " + resolved);
                    return resolved;
                } catch (IOException ignored) {
                    debug("ASM class-pool fallback failed for " + type1 + " / " + type2 + ": " + ignored);
                    return "java/lang/Object";
                }
            } catch (RuntimeException e) {
                return "java/lang/Object";
            }
        }
    }

    private static void debug(String message) {
        if (Boolean.getBoolean("finality.debug")) System.out.println("[DEBUG] " + message);
    }

    private String commonSuperClass(String type1, String type2) throws IOException {
        if (type1.equals(type2)) return type1;
        ClassInfo first = classInfo(type1);
        ClassInfo second = classInfo(type2);
        if (isAssignableFrom(type1, type2)) return type1;
        if (isAssignableFrom(type2, type1)) return type2;
        if (first.isInterface || second.isInterface) return "java/lang/Object";
        String parent = first.superName;
        while (parent != null) {
            if (isAssignableFrom(parent, type2)) return parent;
            parent = classInfo(parent).superName;
        }
        return "java/lang/Object";
    }

    private ClassInfo classInfo(String name) throws IOException {
        byte[] bytes = classPool.get(name + ".class");
        if (bytes == null) {
            String resource = name + ".class";
            try (java.io.InputStream input = classLoader.getResourceAsStream(resource)) {
                if (input == null) throw new IOException("Class not found: " + name);
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) >= 0) if (count > 0) output.write(buffer, 0, count);
                bytes = output.toByteArray();
            }
        }
        ClassReader reader = new ClassReader(bytes);
        return new ClassInfo(name, reader.getSuperName(), (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0, reader.getInterfaces());
    }

    private boolean isAssignableFrom(String target, String candidate) throws IOException {
        if (target.equals(candidate) || "java/lang/Object".equals(target)) return true;
        ClassInfo child = classInfo(candidate);
        if (child.superName != null && isAssignableFrom(target, child.superName)) return true;
        for (String iface : child.interfaces) if (isAssignableFrom(target, iface)) return true;
        return false;
    }

    private final class ClassInfo {
        final String name;
        final String superName;
        final boolean isInterface;
        final String[] interfaces;

        ClassInfo(String name, String superName, boolean isInterface, String[] interfaces) {
            this.name = name;
            this.superName = superName;
            this.isInterface = isInterface;
            this.interfaces = interfaces;
        }
    }

    private void transform(ClassNode target, ClassNode mixin) throws LumiPatchException {
        Map<String, MethodNode> methods = new HashMap<String, MethodNode>();
        for (MethodNode method : target.methods) methods.put(method.name + method.desc, method);
        for (FieldNode field : mixin.fields) {
            if (!hasAnnotation(field.visibleAnnotations, DUMMY) && !containsField(target, field.name, field.desc)) {
                target.fields.add(copyField(field));
            }
        }
        List<MethodNode> additions = new ArrayList<MethodNode>();
        for (MethodNode source : mixin.methods) {
            if (hasAnnotation(source.visibleAnnotations, DUMMY)) continue;
            if (hasAnnotation(source.visibleAnnotations, INJECT)) {
                inject(target, mixin.name, source);
                continue;
            }
            if (hasAnnotation(source.visibleAnnotations, PROXY)) {
                proxy(target, mixin, source);
                continue;
            }
            MethodNode replacement = copyMethod(source, mixin.name, target.name);
            MethodNode old = methods.get(source.name + source.desc);
            boolean explicitlyOverwrite = hasAnnotation(source.visibleAnnotations, OVERWRITE);
            boolean specialInitializer = INIT.equals(source.name) || CLINIT.equals(source.name);
            if (specialInitializer && !explicitlyOverwrite) continue;
            if (explicitlyOverwrite || old != null) {
                if (old == null) throw new LumiPatchException("@Overwrite target method not found: " + source.name + source.desc);
                target.methods.remove(old);
                additions.add(replacement);
                methods.remove(source.name + source.desc);
            } else {
                additions.add(replacement);
            }
        }
        target.methods.addAll(additions);
    }

    private void inject(ClassNode target, String mixinOwner, MethodNode source) throws LumiPatchException {
        AnnotationNode annotation = annotation(source.visibleAnnotations, INJECT);
        String methodName = annotationString(annotation.values, "method");
        AnnotationNode at = annotationAnnotation(annotation.values, "at");
        String value = annotationString(at.values, "value");
        String wanted = annotationString(at.values, "target");
        int ordinal = annotationInt(at.values, "ordinal", -1);
        MethodNode destination = null;
        for (MethodNode candidate : target.methods) if (candidate.name.equals(methodName)) { destination = candidate; break; }
        if (destination == null) throw new LumiPatchException("@Inject target method not found: " + methodName);
        if (!"HEAD".equals(value) && !"TAIL".equals(value) && !"BEFORE_INVOKE".equals(value) && !"AFTER_INVOKE".equals(value)) throw new LumiPatchException("Unsupported @At value: " + value);
        boolean cancellable = annotationBoolean(annotation.values, "cancellable", false);
        boolean dumpLocals = annotationBoolean(annotation.values, "dumpLocals", false);
        List<AbstractInsnNode> points = new ArrayList<AbstractInsnNode>();
        if ("HEAD".equals(value)) points.add(destination.instructions.getFirst());
        else for (AbstractInsnNode instruction = destination.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
            if ("TAIL".equals(value) && instruction.getOpcode() >= Opcodes.IRETURN && instruction.getOpcode() <= Opcodes.RETURN) points.add(instruction);
            if (("BEFORE_INVOKE".equals(value) || "AFTER_INVOKE".equals(value)) && instruction instanceof MethodInsnNode && matches((MethodInsnNode) instruction, wanted)) points.add("BEFORE_INVOKE".equals(value) ? instruction : instruction.getNext());
        }
        if (ordinal >= 0 && ordinal < points.size()) points = java.util.Collections.singletonList(points.get(ordinal));
        else if (ordinal >= 0) points.clear();
        if (points.isEmpty()) throw new LumiPatchException("@Inject point not found in " + methodName);
        boolean targetStatic = isStatic(destination);
        boolean callbackStatic = (source.access & Opcodes.ACC_STATIC) != 0;
        if (targetStatic != callbackStatic) throw new LumiPatchException("Inject method static/virtual mode must match target method: " + source.name + source.desc);
        String callbackName = uniqueGeneratedName(target, "lumipatch$" + source.name, source.desc);
        MethodNode callbackMethod = copyMethod(source, mixinOwner, target.name);
        callbackMethod.name = callbackName;
        target.methods.add(callbackMethod);
        InsnList call = callback(mixinOwner, target.name, callbackName, source, destination, cancellable, dumpLocals);
        for (AbstractInsnNode point : points) {
            InsnList copy = cloneInstructions(call);
            if (point == null) destination.instructions.add(copy); else destination.instructions.insertBefore(point, copy);
        }
    }

    private InsnList callback(String mixinOwner, String targetOwner, String callbackName, MethodNode source, MethodNode destination, boolean cancellable, boolean dumpLocals) throws LumiPatchException {
        Type[] args = Type.getArgumentTypes(destination.desc);
        Type[] callbackArgs = Type.getArgumentTypes(source.desc);
        int expected = args.length + 1;
        if (callbackArgs.length != expected || !CALLBACK_INFO.equals(callbackArgs[callbackArgs.length - 1].getInternalName()) || Type.getReturnType(source.desc) != Type.VOID_TYPE) throw new LumiPatchException("Inject handler must accept target arguments and end with CallbackInfo: " + source.name + source.desc);
        InsnList result = new InsnList();
        int callbackSlot = localCount(destination);
        int localsSlot = callbackSlot + 1;
        int sizesSlot = callbackSlot + 2;
        int typesSlot = callbackSlot + 3;
        if (dumpLocals) {
            int count = localCount(destination);
            pushInt(result, count);
            result.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
            Set<Integer> dumped = new HashSet<Integer>();
            if (destination.localVariables != null) for (LocalVariableNode variable : destination.localVariables) {
                Type localType = Type.getType(variable.desc);
                if (dumped.add(variable.index)) {
                    result.add(new InsnNode(Opcodes.DUP)); pushInt(result, variable.index);
                    result.add(new VarInsnNode(localType.getOpcode(Opcodes.ILOAD), variable.index)); box(result, localType);
                    result.add(new InsnNode(Opcodes.AASTORE));
                }
            }
            result.add(new VarInsnNode(Opcodes.ASTORE, localsSlot));
            emitLocalMetadata(result, destination, count, false); result.add(new VarInsnNode(Opcodes.ASTORE, sizesSlot));
            emitLocalMetadata(result, destination, count, true); result.add(new VarInsnNode(Opcodes.ASTORE, typesSlot));
        }
        result.add(new TypeInsnNode(Opcodes.NEW, CALLBACK_INFO));
        result.add(new InsnNode(Opcodes.DUP));
        result.add(new LdcInsnNode(destination.name));
        result.add(new InsnNode(cancellable ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        if (dumpLocals) {
            result.add(new VarInsnNode(Opcodes.ALOAD, localsSlot));
            result.add(new VarInsnNode(Opcodes.ALOAD, sizesSlot));
            result.add(new VarInsnNode(Opcodes.ALOAD, typesSlot));
        } else result.add(new InsnNode(Opcodes.ACONST_NULL));
        if (!dumpLocals) {
            // Constructor overload without metadata only consumes the locals array.
        }
        result.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, CALLBACK_INFO, "<init>", dumpLocals ? "(Ljava/lang/String;Z[Ljava/lang/Object;[I[Ljava/lang/String;)V" : "(Ljava/lang/String;Z[Ljava/lang/Object;)V", false));
        result.add(new VarInsnNode(Opcodes.ASTORE, callbackSlot));
        int slot = isStatic(destination) ? 0 : 1;
        for (Type arg : args) { result.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), slot)); slot += arg.getSize(); }
        if (!isStatic(destination)) result.insert(new VarInsnNode(Opcodes.ALOAD, 0));
        result.add(new VarInsnNode(Opcodes.ALOAD, callbackSlot));
        result.add(new MethodInsnNode(isStatic(destination) ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL, targetOwner, callbackName, source.desc, false));
        if (cancellable) {
            LabelNode proceed = new LabelNode();
            result.add(new VarInsnNode(Opcodes.ALOAD, callbackSlot));
            result.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CALLBACK_INFO, "isCancelled", "()Z", false));
            result.add(new JumpInsnNode(Opcodes.IFEQ, proceed));
            addCancelledReturn(result, Type.getReturnType(destination.desc), callbackSlot);
            result.add(proceed);
        }
        return result;
    }

    private void emitLocalMetadata(InsnList result, MethodNode method, int localCount, boolean types) {
        pushInt(result, localCount);
        result.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
        if (types) {
            result.remove(result.getLast());
            result.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/String"));
        }
        Map<Integer, Type> metadata = new HashMap<Integer, Type>();
        if (method.localVariables != null) for (LocalVariableNode variable : method.localVariables) metadata.put(variable.index, Type.getType(variable.desc));
        for (Map.Entry<Integer, Type> entry : metadata.entrySet()) {
            result.add(new InsnNode(Opcodes.DUP));
            pushInt(result, entry.getKey());
            if (types) result.add(new LdcInsnNode(entry.getValue().getClassName()));
            else pushInt(result, entry.getValue().getSize());
            result.add(new InsnNode(types ? Opcodes.AASTORE : Opcodes.IASTORE));
        }
    }

    private void addCancelledReturn(InsnList result, Type type, int callbackSlot) {
        if (type == Type.VOID_TYPE) { result.add(new InsnNode(Opcodes.RETURN)); return; }
        result.add(new VarInsnNode(Opcodes.ALOAD, callbackSlot));
        result.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CALLBACK_INFO, "hasReturnValue", "()Z", false));
        LabelNode defaultValue = new LabelNode();
        result.add(new JumpInsnNode(Opcodes.IFEQ, defaultValue));
        result.add(new VarInsnNode(Opcodes.ALOAD, callbackSlot));
        result.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CALLBACK_INFO, "getReturnValue", "()Ljava/lang/Object;", false));
        unbox(result, type);
        result.add(new InsnNode(type.getOpcode(Opcodes.IRETURN)));
        result.add(defaultValue);
        addDefaultReturn(result, type);
    }

    private void unbox(InsnList result, Type type) {
        if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) { result.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getInternalName())); return; }
        String owner;
        String method;
        String desc;
        switch (type.getSort()) {
            case Type.BOOLEAN: owner = "java/lang/Boolean"; method = "booleanValue"; desc = "()Z"; break;
            case Type.BYTE: owner = "java/lang/Byte"; method = "byteValue"; desc = "()B"; break;
            case Type.CHAR: owner = "java/lang/Character"; method = "charValue"; desc = "()C"; break;
            case Type.SHORT: owner = "java/lang/Short"; method = "shortValue"; desc = "()S"; break;
            case Type.INT: owner = "java/lang/Integer"; method = "intValue"; desc = "()I"; break;
            case Type.LONG: owner = "java/lang/Long"; method = "longValue"; desc = "()J"; break;
            case Type.FLOAT: owner = "java/lang/Float"; method = "floatValue"; desc = "()F"; break;
            case Type.DOUBLE: owner = "java/lang/Double"; method = "doubleValue"; desc = "()D"; break;
            default: throw new IllegalArgumentException("Unsupported return type: " + type);
        }
        result.add(new TypeInsnNode(Opcodes.CHECKCAST, owner));
        result.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, method, desc, false));
    }

    private void proxy(ClassNode target, ClassNode mixin, MethodNode source) throws LumiPatchException {
        if ((source.access & Opcodes.ACC_PUBLIC) == 0 || (source.access & Opcodes.ACC_STATIC) == 0) throw new LumiPatchException("@Proxy method must be public static: " + source.name + source.desc);
        MethodNode targetMethod = copyMethod(source, mixin.name, target.name);
        targetMethod.access = (source.access & ~(Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) | Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
        if (containsMethod(target, source.name, source.desc)) throw new LumiPatchException("Proxy method already exists: " + source.name + source.desc);
        target.methods.add(targetMethod);
        proxyFacade(target, source);
    }

    private void proxyFacade(ClassNode target, MethodNode source) {
        source.instructions.clear();
        int slot = 0;
        for (Type arg : Type.getArgumentTypes(source.desc)) { source.instructions.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), slot)); slot += arg.getSize(); }
        source.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, target.name, source.name, source.desc, false));
        source.instructions.add(new InsnNode(Type.getReturnType(source.desc).getOpcode(Opcodes.IRETURN)));
    }

    private boolean isStatic(MethodNode method) { return (method.access & Opcodes.ACC_STATIC) != 0; }
    private void addDefaultReturn(InsnList result, Type type) {
        if (type == Type.VOID_TYPE) result.add(new InsnNode(Opcodes.RETURN));
        else if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) { result.add(new InsnNode(Opcodes.ACONST_NULL)); result.add(new InsnNode(Opcodes.ARETURN)); }
        else if (type == Type.LONG_TYPE) { result.add(new InsnNode(Opcodes.LCONST_0)); result.add(new InsnNode(Opcodes.LRETURN)); }
        else if (type == Type.DOUBLE_TYPE) { result.add(new InsnNode(Opcodes.DCONST_0)); result.add(new InsnNode(Opcodes.DRETURN)); }
        else if (type == Type.FLOAT_TYPE) { result.add(new InsnNode(Opcodes.FCONST_0)); result.add(new InsnNode(Opcodes.FRETURN)); }
        else { result.add(new InsnNode(Opcodes.ICONST_0)); result.add(new InsnNode(Opcodes.IRETURN)); }
    }

    private void box(InsnList result, Type type) {
        if (type.getSort() == Type.BOOLEAN) result.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false));
        else if (type.getSort() == Type.BYTE) result.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false));
        else if (type.getSort() == Type.CHAR) result.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false));
        else if (type.getSort() == Type.SHORT) result.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false));
        else if (type.getSort() == Type.INT) result.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
        else if (type.getSort() == Type.LONG) result.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false));
        else if (type.getSort() == Type.FLOAT) result.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false));
        else if (type.getSort() == Type.DOUBLE) result.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false));
    }

    private void pushInt(InsnList result, int value) {
        if (value >= -1 && value <= 5) result.add(new InsnNode(Opcodes.ICONST_0 + value));
        else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) result.add(new IntInsnNode(Opcodes.BIPUSH, value));
        else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) result.add(new IntInsnNode(Opcodes.SIPUSH, value));
        else result.add(new LdcInsnNode(Integer.valueOf(value)));
    }

    private int localCount(MethodNode method) {
        int count = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (Type argument : Type.getArgumentTypes(method.desc)) count += argument.getSize();
        count = Math.max(count, Math.max(0, method.maxLocals));
        if (method.localVariables != null) {
            for (LocalVariableNode local : method.localVariables) {
                int size = Type.getType(local.desc).getSize();
                count = Math.max(count, local.index + size);
            }
        }
        return count;
    }

    private InsnList cloneInstructions(InsnList source) {
        Map<LabelNode, LabelNode> labels = new HashMap<LabelNode, LabelNode>();
        for (AbstractInsnNode node = source.getFirst(); node != null; node = node.getNext()) if (node instanceof LabelNode) labels.put((LabelNode) node, new LabelNode());
        InsnList result = new InsnList();
        for (AbstractInsnNode node = source.getFirst(); node != null; node = node.getNext()) result.add(node.clone(labels));
        return result;
    }

    private boolean matches(MethodInsnNode node, String target) {
        if (target == null || target.isEmpty()) return true;
        int open = target.indexOf('(');
        String name = open < 0 ? target : target.substring(target.lastIndexOf('.', open) + 1, open);
        String desc = open < 0 ? "" : target.substring(open);
        return node.name.equals(name) && (desc.isEmpty() || node.desc.equals(desc));
    }

    private ClassNode read(byte[] bytes) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node; }
    private FieldNode copyField(FieldNode f) { return new FieldNode(f.access, f.name, f.desc, f.signature, f.value); }
    private MethodNode copyMethod(MethodNode m, final String mixinOwner, final String targetOwner) {
        SimpleRemapper remapper = new SimpleRemapper(mixinOwner, targetOwner);
        String[] exceptions = null;
        if (m.exceptions != null) {
            exceptions = new String[m.exceptions.size()];
            for (int i = 0; i < exceptions.length; i++) exceptions[i] = remapper.mapType(m.exceptions.get(i));
        }
        MethodNode copy = new MethodNode(m.access, m.name, remapper.mapMethodDesc(m.desc), remapper.mapSignature(m.signature, false), exceptions);
        m.accept(new MethodRemapper(copy, remapper));
        return copy;
    }
    private boolean containsField(ClassNode n, String name, String desc) { for (FieldNode f : n.fields) if (f.name.equals(name) && f.desc.equals(desc)) return true; return false; }
    private boolean containsMethod(ClassNode n, String name, String desc) { for (MethodNode m : n.methods) if (m.name.equals(name) && m.desc.equals(desc)) return true; return false; }
    private String uniqueGeneratedName(ClassNode node, String base, String desc) {
        String name = base;
        int suffix = 1;
        while (containsMethod(node, name, desc)) name = base + "$" + suffix++;
        return name;
    }
    private boolean hasAnnotation(List<AnnotationNode> annotations, String descriptor) { return annotation(annotations, descriptor) != null; }
    private AnnotationNode annotation(List<AnnotationNode> annotations, String descriptor) { if (annotations != null) for (AnnotationNode a : annotations) if (descriptor.equals(a.desc)) return a; return null; }
    private String annotationString(List<Object> values, String key) { if (values != null) for (int i = 0; i + 1 < values.size(); i += 2) if (key.equals(values.get(i))) return (String) values.get(i + 1); return null; }
    private String annotationString(List<AnnotationNode> annotations, String descriptor, String key) { AnnotationNode a = annotation(annotations, descriptor); return a == null ? null : annotationString(a.values, key); }
    private AnnotationNode annotationAnnotation(List<Object> values, String key) { if (values != null) for (int i = 0; i + 1 < values.size(); i += 2) if (key.equals(values.get(i))) return (AnnotationNode) values.get(i + 1); return null; }
    private int annotationInt(List<Object> values, String key, int fallback) { if (values != null) for (int i = 0; i + 1 < values.size(); i += 2) if (key.equals(values.get(i))) return ((Integer) values.get(i + 1)).intValue(); return fallback; }
    private boolean annotationBoolean(List<Object> values, String key, boolean fallback) { if (values != null) for (int i = 0; i + 1 < values.size(); i += 2) if (key.equals(values.get(i))) return ((Boolean) values.get(i + 1)).booleanValue(); return fallback; }
    private boolean annotationBoolean(List<AnnotationNode> annotations, String descriptor, String key, boolean fallback) { AnnotationNode a = annotation(annotations, descriptor); return a == null ? fallback : annotationBoolean(a.values, key, fallback); }
}
