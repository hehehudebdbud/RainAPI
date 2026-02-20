package net.rain.api.mixin.transformer;

import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import javassist.*;
import javassist.bytecode.*;
import javassist.expr.*;
import net.rain.api.core.java.helper.MinecraftHelper;
import net.rain.api.mixin.IMixin;
import net.rain.api.mixin.annotation.*;
import net.rain.api.mixin.manager.MixinManager;
import net.rain.api.mixin.manager.MixinRegistry;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

public class MixinTransformer implements ILaunchPluginService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MixinTransformer.class);
    private static final ClassPool classPool = ClassPool.getDefault();
    private static boolean classPoolInitialized = false;

    static {
        initializeClassPool();
    }

    private static synchronized void initializeClassPool() {
        if (classPoolInitialized) return;
        try {
            classPool.appendSystemPath();

            String systemClassPath = System.getProperty("java.class.path");
            if (systemClassPath != null && !systemClassPath.isEmpty()) {
                for (String entry : systemClassPath.split(File.pathSeparator)) {
                    try {
                        classPool.appendClassPath(entry);
                    } catch (NotFoundException e) {
                        LOGGER.warn("Cannot add classpath entry: {}", entry);
                    }
                }
            }

            ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
            if (contextLoader != null) appendClassLoaderPath(classPool, contextLoader);

            ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
            if (systemLoader != contextLoader) appendClassLoaderPath(classPool, systemLoader);

            scanMinecraftDirectory(classPool);
            classPool.childFirstLookup = true;
            classPoolInitialized = true;
        } catch (Exception e) {
            LOGGER.error("Failed to initialize ClassPool", e);
        }
    }

    private static void scanMinecraftDirectory(ClassPool pool) {
        try {
            Path minecraftDir = findMinecraftDirectory();
            if (minecraftDir == null || !Files.exists(minecraftDir)) return;
            try (Stream<Path> paths = Files.walk(minecraftDir)) {
                paths.filter(Files::isRegularFile)
                        .filter(p -> p.toString().toLowerCase().endsWith(".jar"))
                        .forEach(jar -> {
                            try {
                                pool.appendClassPath(jar.toAbsolutePath().toString());
                            } catch (Exception e) {
                                LOGGER.debug("Cannot add JAR: {}", jar, e);
                            }
                        });
            }
        } catch (Exception e) {
            LOGGER.error("Failed to scan .minecraft directory", e);
        }
    }

    private static Path findMinecraftDirectory() {
        String gameDir = System.getProperty("minecraft.gameDir");
        if (gameDir != null) {
            Path p = Paths.get(gameDir);
            if (Files.exists(p)) {
                if (p.toString().contains("/versions/")) p = p.getParent().getParent();
                return p;
            }
        }
        try {
            String[] args = System.getProperty("sun.java.command", "").split(" ");
            for (int i = 0; i < args.length - 1; i++) {
                if (args[i].equals("--gameDir")) {
                    Path p = Paths.get(args[i + 1]);
                    if (Files.exists(p)) {
                        if (p.toString().contains("/versions/")) p = p.getParent().getParent();
                        return p;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (entry.contains(".minecraft")) {
                Path p = Paths.get(entry);
                while (p != null && !p.getFileName().toString().equals(".minecraft"))
                    p = p.getParent();
                if (p != null && Files.exists(p)) return p;
            }
        }
        String home = System.getProperty("user.home");
        for (String s : new String[]{
                "/storage/emulated/0/FCL/.minecraft",
                home + "/.minecraft",
                home + "/AppData/Roaming/.minecraft",
                home + "/Library/Application Support/minecraft"
        }) {
            Path p = Paths.get(s);
            if (Files.exists(p)) return p;
        }
        return null;
    }

    private static void appendClassLoaderPath(ClassPool pool, ClassLoader loader) {
        if (!(loader instanceof java.net.URLClassLoader)) return;
        for (URL url : ((java.net.URLClassLoader) loader).getURLs()) {
            try {
                pool.appendClassPath(java.net.URLDecoder.decode(url.getFile(), "UTF-8"));
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public String name() {
        return "!!MixinTransformer";
    }

    @Override
    public EnumSet<Phase> handlesClass(Type classType, boolean isEmpty) {
        if (isExcludedPackage(classType.getClassName())) return EnumSet.noneOf(Phase.class);
        return EnumSet.of(Phase.AFTER);
    }

    @Override
    public boolean processClass(Phase phase, org.objectweb.asm.tree.ClassNode classNode,
            Type classType, String reason) {
        if (phase != Phase.AFTER || !"classloading".equals(reason)) return false;

        String className = classNode.name.replace('/', '.');

        // Prevent double-processing the same class
        if (!MixinManager.markTransforming(className)) return false;

        ClassLoader gameClassLoader = Thread.currentThread().getContextClassLoader();
        if (gameClassLoader == null) gameClassLoader = ClassLoader.getSystemClassLoader();

        if (!MixinManager.hasMixins(className, gameClassLoader)) return false;

        try {
            List<Class<?>> mixins = MixinManager.getMixinsFor(className, gameClassLoader);
            if (mixins.isEmpty()) return false;

            LOGGER.info("Applying {} mixin(s) to {}", mixins.size(), className);
            sortMixinsByPriority(mixins);

            org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
            classNode.accept(writer);
            byte[] originalBytecode = writer.toByteArray();

            CtClass targetClass = classPool.makeClass(new ByteArrayInputStream(originalBytecode));

            for (Class<?> mixinClass : mixins) {
                if (isPseudoMixin(mixinClass) && !handlePseudoMixin(targetClass, mixinClass))
                    continue;
            }
            for (Class<?> mixinClass : mixins) {
                try {
                    applyMixin(targetClass, mixinClass);
                } catch (Exception e) {
                    throw new net.rain.api.mixin.throwables.MixinApplyError(e.getMessage());
                }
            }

            byte[] modifiedBytecode = targetClass.toBytecode();
            org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(modifiedBytecode);
            org.objectweb.asm.tree.ClassNode newNode = new org.objectweb.asm.tree.ClassNode();
            reader.accept(newNode, 0);

            classNode.version = newNode.version;
            classNode.access = newNode.access;
            classNode.name = newNode.name;
            classNode.signature = newNode.signature;
            classNode.superName = newNode.superName;
            classNode.interfaces = newNode.interfaces;
            classNode.sourceFile = newNode.sourceFile;
            classNode.sourceDebug = newNode.sourceDebug;
            classNode.outerClass = newNode.outerClass;
            classNode.outerMethod = newNode.outerMethod;
            classNode.outerMethodDesc = newNode.outerMethodDesc;
            classNode.visibleAnnotations = newNode.visibleAnnotations;
            classNode.invisibleAnnotations = newNode.invisibleAnnotations;
            classNode.visibleTypeAnnotations = newNode.visibleTypeAnnotations;
            classNode.invisibleTypeAnnotations = newNode.invisibleTypeAnnotations;
            classNode.attrs = newNode.attrs;
            classNode.innerClasses = newNode.innerClasses;
            classNode.nestHostClass = newNode.nestHostClass;
            classNode.nestMembers = newNode.nestMembers;
            classNode.permittedSubclasses = newNode.permittedSubclasses;
            classNode.recordComponents = newNode.recordComponents;
            classNode.methods.clear();
            classNode.methods.addAll(newNode.methods);
            classNode.fields.clear();
            classNode.fields.addAll(newNode.fields);

            targetClass.detach();
            LOGGER.info("Successfully applied {} mixin(s) to {}", mixins.size(), className);
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to apply mixin to {}", className, e);
            return false;
        }
    }

    private void applyMixin(CtClass targetClass, Class<?> mixinClass) throws Exception {
        LOGGER.info("Applying mixin: {} -> {}", mixinClass.getName(), targetClass.getName());

        MixinManager.MixinMetadata metadata = MixinManager.getMetadata(mixinClass.getName());
        if (metadata == null) {
            throw new IllegalStateException("No cached bytecode for mixin: " + mixinClass.getName());
        }

        // Get a CtClass for the mixin from its cached bytecode
        CtClass ctMixinClass = classPool.getOrNull(mixinClass.getName());
        boolean shouldDetach = false;
        if (ctMixinClass == null) {
            ctMixinClass = classPool.makeClass(new ByteArrayInputStream(metadata.bytecode));
            shouldDetach = true;
        }

        try {
            applyClassAnnotations(targetClass, ctMixinClass);
            applyFieldAnnotations(targetClass, ctMixinClass);

            for (CtMethod ctMethod : ctMixinClass.getDeclaredMethods()) {
                try {
                    applyMethodAnnotations(targetClass, mixinClass, ctMethod);
                } catch (Exception e) {
                    throw new net.rain.api.mixin.throwables.MixinApplyError(e.getMessage());
                }
            }
        } finally {
            if (shouldDetach) ctMixinClass.detach();
        }

        LOGGER.info("Mixin applied successfully: {} -> {}", mixinClass.getName(), targetClass.getName());
    }


    private void applyClassAnnotations(CtClass targetClass, CtClass ctMixinClass) throws Exception {
        Implements impl = (Implements) ctMixinClass.getAnnotation(Implements.class);
        if (impl != null) {
            for (Class<?> iface : impl.value()) {
                CtClass ifaceClass = classPool.get(iface.getName());
                targetClass.addInterface(ifaceClass);
                LOGGER.info("Added interface: {} to {}", iface.getName(), targetClass.getName());
            }
        }
    }

    // ==================== Field-level annotations ====================

    private void applyFieldAnnotations(CtClass targetClass, CtClass ctMixinClass) throws Exception {
        for (CtField ctField : ctMixinClass.getDeclaredFields()) {
            Shadow shadow = (Shadow) ctField.getAnnotation(Shadow.class);
            if (shadow != null) {
                applyShadowField(targetClass, ctField, shadow);
                continue;
            }
            Unique unique = (Unique) ctField.getAnnotation(Unique.class);
            if (unique != null) {
                applyUniqueField(targetClass, ctField, unique);
            }
        }
    }

    private void applyShadowField(CtClass targetClass, CtField ctField, Shadow shadow)
            throws Exception {
        String targetName = shadow.target().isEmpty() ? ctField.getName() : shadow.target();
        try {
            CtField targetField = targetClass.getField(targetName);
            if (ctField.getAnnotation(Mutable.class) != null) {
                targetField.setModifiers(Modifier.clear(targetField.getModifiers(), Modifier.FINAL));
            } else if (ctField.getAnnotation(Final.class) != null) {
                targetField.setModifiers(Modifier.setPublic(targetField.getModifiers()) | Modifier.FINAL);
            }
        } catch (NotFoundException e) {
            if (!shadow.optional()) {
                LOGGER.error("Shadow field not found: {}.{}", targetClass.getName(), targetName);
                throw e;
            }
        }
    }

    private void applyUniqueField(CtClass targetClass, CtField ctField, Unique unique)
            throws Exception {
        String fieldName = ctField.getName();
        try {
            targetClass.getField(fieldName);
            if (!unique.silent()) {
                LOGGER.warn("Unique field already exists: {}.{}", targetClass.getName(), fieldName);
            }
            return;
        } catch (NotFoundException ignored) {
        }
        CtField newField = new CtField(ctField.getType(), fieldName, targetClass);
        newField.setModifiers(ctField.getModifiers());
        targetClass.addField(newField);
        LOGGER.info("Added unique field: {}.{}", targetClass.getName(), fieldName);
    }

    // ==================== Method-level annotations ====================

    private void applyMethodAnnotations(CtClass targetClass, Class<?> mixinClass, CtMethod ctMethod)
            throws Exception {
        if (ctMethod.getAnnotation(Shadow.class) != null) return;
        if (ctMethod.getAnnotation(Inject.class) != null)
            applyInject(targetClass, mixinClass, ctMethod);
        if (ctMethod.getAnnotation(Overwrite.class) != null)
            applyOverwrite(targetClass, mixinClass, ctMethod);
        if (ctMethod.getAnnotation(Redirect.class) != null)
            applyRedirect(targetClass, mixinClass, ctMethod);
        if (ctMethod.getAnnotation(ModifyConstant.class) != null)
            applyModifyConstant(targetClass, mixinClass, ctMethod);
        if (ctMethod.getAnnotation(ModifyArg.class) != null)
            applyModifyArg(targetClass, mixinClass, ctMethod);
        if (ctMethod.getAnnotation(ModifyArgs.class) != null)
            applyModifyArgs(targetClass, mixinClass, ctMethod);
        if (ctMethod.getAnnotation(ModifyVariable.class) != null)
            applyModifyVariable(targetClass, mixinClass, ctMethod);
        if (ctMethod.getAnnotation(Unique.class) != null)
            applyUniqueMethod(targetClass, mixinClass, ctMethod);
        if (ctMethod.getAnnotation(Accessor.class) != null) applyAccessor(targetClass, ctMethod);
        if (ctMethod.getAnnotation(Invoker.class) != null) applyInvoker(targetClass, ctMethod);
    }

    // ==================== @Inject ====================

    private void applyInject(CtClass targetClass, Class<?> mixinClass, CtMethod ctMethod)
            throws Exception {
        Inject inject = (Inject) ctMethod.getAnnotation(Inject.class);
        CtMethod targetMethod = findMethod(targetClass, inject.method(), inject.descriptor());
        if (targetMethod == null) {
            if (inject.require()) {
                LOGGER.warn("Inject target not found: {}.{}{}", targetClass.getName(), inject.method(), inject.descriptor());
            }
            return;
        }

        Object mixinInstance = MixinRegistry.getInstance(mixinClass.getName());
        if (mixinInstance == null) {
            LOGGER.error("Mixin instance not found: {}", mixinClass.getName());
            return;
        }

        Inject.At at = inject.at();
        switch (at.value()) {
            case "HEAD":
                injectAtHead(targetMethod, mixinClass, ctMethod, inject.cancellable());
                break;
            case "RETURN":
                injectAtReturn(targetMethod, mixinClass, ctMethod, inject.cancellable());
                break;
            case "INVOKE":
                injectAtInvoke(targetMethod, mixinClass, ctMethod, at, inject.cancellable());
                break;
            case "FIELD":
                injectAtField(targetMethod, mixinClass, ctMethod, at, inject.cancellable());
                break;
            case "TAIL":
                injectAtTail(targetMethod, mixinClass, ctMethod, inject.cancellable());
                break;
            default:
                LOGGER.warn("Unsupported injection point: {}", at.value());
        }
        LOGGER.info("Injected: {} into {}.{}", ctMethod.getName(), targetClass.getName(), targetMethod.getName());
    }

    private void injectAtHead(CtMethod targetMethod, Class<
                    ?> mixinClass, CtMethod ctMethod, boolean cancellable)
            throws Exception {
        String code = buildCallbackCode(targetMethod, mixinClass, ctMethod, cancellable);
        try {
            targetMethod.insertBefore(code);
        } catch (CannotCompileException e) {
            LOGGER.error("Failed to insert code at HEAD. Code: {}", code);
            throw e;
        }
    }

    private void injectAtReturn(CtMethod targetMethod, Class<
                    ?> mixinClass, CtMethod ctMethod, boolean cancellable)
            throws Exception {
        boolean hasReturn = !targetMethod.getReturnType().equals(CtClass.voidType);
        targetMethod.insertAfter(buildReturnCallbackCode(targetMethod, mixinClass, ctMethod, cancellable, hasReturn), false);
    }

    private void injectAtTail(CtMethod targetMethod, Class<
                    ?> mixinClass, CtMethod ctMethod, boolean cancellable)
            throws Exception {
        targetMethod.insertAfter(buildCallbackCode(targetMethod, mixinClass, ctMethod, cancellable), true);
    }

    private void injectAtInvoke(CtMethod targetMethod, Class<?> mixinClass, CtMethod ctMethod,
            Inject.At at, boolean cancellable)
            throws Exception {
        String target = at.target();
        if (target.isEmpty()) {
            LOGGER.warn("INVOKE injection missing target");
            return;
        }
        final int ordinal = at.ordinal();
        final boolean before = at.before();
        final int[] count = {0};
        targetMethod.instrument(new ExprEditor() {
            @Override
            public void edit(MethodCall m) throws CannotCompileException {
                String sig = m.getClassName() + "." + m.getMethodName();
                if (sig.equals(target) || m.getMethodName().equals(target)) {
                    if (ordinal == -1 || count[0] == ordinal) {
                        try {
                            String code = buildCallbackCode(targetMethod, mixinClass, ctMethod, cancellable);
                            m.replace(before ? "{ " + code + " $_ = $proceed($$); }" : "{ $_ = $proceed($$); " + code + " }");
                        } catch (Exception e) {
                            throw new CannotCompileException(e);
                        }
                    }
                    count[0]++;
                }
            }
        });
    }

    private void injectAtField(CtMethod targetMethod, Class<?> mixinClass, CtMethod ctMethod,
            Inject.At at, boolean cancellable)
            throws Exception {
        String target = at.target();
        if (target.isEmpty()) {
            LOGGER.warn("FIELD injection missing target");
            return;
        }
        final int ordinal = at.ordinal();
        final boolean before = at.before();
        final int[] count = {0};
        targetMethod.instrument(new ExprEditor() {
            @Override
            public void edit(FieldAccess f) throws CannotCompileException {
                if (f.getFieldName().equals(target)) {
                    if (ordinal == -1 || count[0] == ordinal) {
                        try {
                            String code = buildCallbackCode(targetMethod, mixinClass, ctMethod, cancellable);
                            if (before) {
                                f.replace(f.isReader() ? "{ " + code + " $_ = $proceed(); }" : "{ " + code + " $proceed($$); }");
                            } else {
                                f.replace(f.isReader() ? "{ $_ = $proceed(); " + code + " }" : "{ $proceed($$); " + code + " }");
                            }
                        } catch (Exception e) {
                            throw new CannotCompileException(e);
                        }
                    }
                    count[0]++;
                }
            }
        });
    }

    // ==================== @Overwrite ====================

    private void applyOverwrite(CtClass targetClass, Class<?> mixinClass, CtMethod ctMethod)
            throws Exception {
        Overwrite overwrite = (Overwrite) ctMethod.getAnnotation(Overwrite.class);
        CtMethod targetMethod = findMethod(targetClass, overwrite.method(), overwrite.descriptor());
        if (targetMethod == null) {
            LOGGER.warn("Overwrite target not found: {}.{}", targetClass.getName(), overwrite.method());
            return;
        }

        boolean isStatic = (targetMethod.getModifiers() & Modifier.STATIC) != 0;
        CtClass[] targetParams = targetMethod.getParameterTypes();
        CtClass[] mixinParams = ctMethod.getParameterTypes();
        CtClass returnType = targetMethod.getReturnType();

        StringBuilder body = new StringBuilder("{\n");
        body.append("    Object _mi = net.rain.api.mixin.manager.MixinRegistry.getInstance(\"")
                .append(mixinClass.getName()).append("\");\n");
        body.append("    Class[] _pt = new Class[]{");
        for (int i = 0; i < mixinParams.length; i++) {
            if (i > 0) body.append(", ");
            body.append(mixinParams[i].getName()).append(".class");
        }
        body.append("};\n");
        body.append("    Object[] _args = new Object[]{").append(isStatic ? "null" : "$0");
        for (int i = 0; i < targetParams.length; i++) {
            body.append(", ").append(boxPrimitive(targetParams[i], "$" + (i + 1)));
        }
        body.append("};\n");
        body.append("    Object _res = _mi.getClass().getMethod(\"")
                .append(ctMethod.getName()).append("\", _pt).invoke(_mi, _args);\n");
        if (returnType.equals(CtClass.voidType)) {
            body.append("    return;\n");
        } else {
            body.append("    return ").append(unboxReturn(returnType, "_res")).append(";\n");
        }
        body.append("}");

        targetMethod.setBody(body.toString());
        LOGGER.info("Overwrote method: {}.{}", targetClass.getName(), targetMethod.getName());
    }

    // ==================== @Redirect ====================

    private void applyRedirect(CtClass targetClass, Class<?> mixinClass, CtMethod ctMethod)
            throws Exception {
        Redirect redirect = (Redirect) ctMethod.getAnnotation(Redirect.class);
        CtMethod targetMethod = findMethod(targetClass, redirect.method(), redirect.descriptor());
        if (targetMethod == null) {
            LOGGER.warn("Redirect target not found: {}.{}", targetClass.getName(), redirect.method());
            return;
        }

        String targetCall = redirect.at().target();
        final int ordinal = redirect.at().ordinal();
        final int[] count = {0};

        targetMethod.instrument(new ExprEditor() {
            @Override
            public void edit(MethodCall m) throws CannotCompileException {
                String sig = m.getClassName() + "." + m.getMethodName();
                if (sig.equals(targetCall) || m.getMethodName().equals(targetCall)) {
                    if (ordinal == -1 || count[0] == ordinal) {
                        try {
                            m.replace(buildRedirectCode(mixinClass, ctMethod, m));
                        } catch (Exception e) {
                            throw new CannotCompileException(e);
                        }
                    }
                    count[0]++;
                }
            }
        });
        LOGGER.info("Redirected call: {} in {}.{}", targetCall, targetClass.getName(), targetMethod.getName());
    }

    // ==================== @ModifyConstant ====================

    private void applyModifyConstant(CtClass targetClass, Class<?> mixinClass, CtMethod ctMethod)
            throws Exception {
        ModifyConstant modify = (ModifyConstant) ctMethod.getAnnotation(ModifyConstant.class);
        CtMethod targetMethod = findMethod(targetClass, modify.method(), modify.descriptor());
        if (targetMethod == null) {
            LOGGER.warn("ModifyConstant target not found: {}.{}", targetClass.getName(), modify.method());
            return;
        }

        boolean isStatic = (targetMethod.getModifiers() & Modifier.STATIC) != 0;
        CtClass retCtType = ctMethod.getReturnType();
        Class<?> retType = Class.forName(retCtType.getName());
        String retDesc = getTypeDescriptor(retType);
        String helperDesc = "(Ljava/lang/Object;" + retDesc + ")" + retDesc;

        String helperName = "$mc$" + mixinClass.getSimpleName().replaceAll("[^a-zA-Z0-9]", "_")
                + "$" + ctMethod.getName();

        boolean helperExists = false;
        try {
            targetClass.getDeclaredMethod(helperName, new CtClass
                    []{classPool.get("java.lang.Object"), retCtType});
            helperExists = true;
        } catch (NotFoundException ignored) {
        }

        if (!helperExists) {
            addModifyConstantHelper(targetClass, mixinClass, ctMethod, helperName, retCtType);
        }

        MethodInfo methodInfo = targetMethod.getMethodInfo();
        ConstPool constPool = methodInfo.getConstPool();
        CodeAttribute ca = methodInfo.getCodeAttribute();
        if (ca == null) return;

        int helperRef = constPool.addMethodrefInfo(
                constPool.addClassInfo(targetClass.getName()),
                helperName,
                helperDesc);

        for (ModifyConstant.Constant constant : modify.constant()) {
            patchConstantBytecode(targetMethod, constant, retType, helperRef, isStatic, constPool, ca);
        }

        methodInfo.rebuildStackMapIf6(classPool, targetClass.getClassFile());
        LOGGER.info("ModifyConstant applied: {}.{}", targetClass.getName(), targetMethod.getName());
    }

    private void addModifyConstantHelper(CtClass targetClass, Class<?> mixinClass,
            CtMethod ctMethod, String helperName, CtClass retCtType)
            throws Exception {
        CtClass[] mixinParams = ctMethod.getParameterTypes();
        CtMethod helper = new CtMethod(retCtType, helperName,
        new CtClass[]{classPool.get("java.lang.Object"), retCtType}, targetClass);
        helper.setModifiers(Modifier.PRIVATE | Modifier.STATIC);

        StringBuilder b = new StringBuilder("{\n");
        b.append("    Object _mi = net.rain.api.mixin.manager.MixinRegistry.getInstance(\"")
                .append(mixinClass.getName()).append("\");\n");
        b.append("    if (_mi == null) return $2;\n");
        b.append("    try {\n");
        b.append("        Class[] _pt = new Class[]{");
        for (int i = 0; i < mixinParams.length; i++) {
            if (i > 0) b.append(", ");
            b.append(mixinParams[i].getName()).append(".class");
        }
        b.append("};\n");
        b.append("        java.lang.reflect.Method _m = _mi.getClass().getMethod(\"")
                .append(ctMethod.getName()).append("\", _pt);\n");
        b.append("        Object[] _args = new Object[]{ $1, ")
                .append(boxPrimitive(retCtType, "$2")).append(" };\n");
        b.append("        return ").append(unboxReturn(retCtType, "_m.invoke(_mi, _args)")).append(";\n");
        b.append("    } catch (Exception _e) { _e.printStackTrace(); }\n");
        b.append("    return $2;\n");
        b.append("}\n");

        helper.setBody(b.toString());
        targetClass.addMethod(helper);
    }

    private void patchConstantBytecode(CtMethod targetMethod, ModifyConstant.Constant constant,
            Class<?> retType, int helperRef, boolean isStatic,
            ConstPool constPool, CodeAttribute ca)
            throws Exception {
        CodeIterator iter = ca.iterator();
        int targetOrdinal = constant.ordinal();
        int occurrences = 0;
        List<int[]> matches = new ArrayList<>();

        while (iter.hasNext()) {
            int pos = iter.next();
            int op = iter.byteAt(pos);
            boolean hit = false;
            int size = 1;

            if (retType == int.class || retType == Integer.class) {
                int v = constant.intValue();
                if (op == Opcode.ICONST_M1 && v == -1) hit = true;
                else if (op == Opcode.ICONST_0 && v == 0) hit = true;
                else if (op == Opcode.ICONST_1 && v == 1) hit = true;
                else if (op == Opcode.ICONST_2 && v == 2) hit = true;
                else if (op == Opcode.ICONST_3 && v == 3) hit = true;
                else if (op == Opcode.ICONST_4 && v == 4) hit = true;
                else if (op == Opcode.ICONST_5 && v == 5) hit = true;
                else if (op == Opcode.BIPUSH) {
                    size = 2;
                    if ((byte) iter.byteAt(pos + 1) == v) hit = true;
                } else if (op == Opcode.SIPUSH) {
                    size = 3;
                    int sv = (short) ((iter.byteAt(pos + 1) << 8) | iter.byteAt(pos + 2));
                    if (sv == v) hit = true;
                } else if (op == Opcode.LDC) {
                    size = 2;
                    int idx = iter.byteAt(pos + 1);
                    if (constPool.getTag(idx) == ConstPool.CONST_Integer && constPool.getIntegerInfo(idx) == v)
                        hit = true;
                } else if (op == Opcode.LDC_W) {
                    size = 3;
                    int idx = (iter.byteAt(pos + 1) << 8) | iter.byteAt(pos + 2);
                    if (constPool.getTag(idx) == ConstPool.CONST_Integer && constPool.getIntegerInfo(idx) == v)
                        hit = true;
                }
            } else if (retType == float.class || retType == Float.class) {
                float v = constant.floatValue();
                if (op == Opcode.FCONST_0 && v == 0f) hit = true;
                else if (op == Opcode.FCONST_1 && v == 1f) hit = true;
                else if (op == Opcode.FCONST_2 && v == 2f) hit = true;
                else if (op == Opcode.LDC) {
                    size = 2;
                    int idx = iter.byteAt(pos + 1);
                    if (constPool.getTag(idx) == ConstPool.CONST_Float && constPool.getFloatInfo(idx) == v)
                        hit = true;
                } else if (op == Opcode.LDC_W) {
                    size = 3;
                    int idx = (iter.byteAt(pos + 1) << 8) | iter.byteAt(pos + 2);
                    if (constPool.getTag(idx) == ConstPool.CONST_Float && constPool.getFloatInfo(idx) == v)
                        hit = true;
                }
            } else if (retType == double.class || retType == Double.class) {
                double v = constant.doubleValue();
                if (op == Opcode.DCONST_0 && v == 0d) hit = true;
                else if (op == Opcode.DCONST_1 && v == 1d) hit = true;
                else if (op == Opcode.LDC2_W) {
                    size = 3;
                    int idx = (iter.byteAt(pos + 1) << 8) | iter.byteAt(pos + 2);
                    if (constPool.getTag(idx) == ConstPool.CONST_Double && constPool.getDoubleInfo(idx) == v)
                        hit = true;
                }
            } else if (retType == long.class || retType == Long.class) {
                long v = constant.longValue();
                if (op == Opcode.LCONST_0 && v == 0L) hit = true;
                else if (op == Opcode.LCONST_1 && v == 1L) hit = true;
                else if (op == Opcode.LDC2_W) {
                    size = 3;
                    int idx = (iter.byteAt(pos + 1) << 8) | iter.byteAt(pos + 2);
                    if (constPool.getTag(idx) == ConstPool.CONST_Long && constPool.getLongInfo(idx) == v)
                        hit = true;
                }
            } else if (retType == String.class) {
                String v = constant.stringValue();
                if (op == Opcode.LDC) {
                    size = 2;
                    int idx = iter.byteAt(pos + 1);
                    if (constPool.getTag(idx) == ConstPool.CONST_String && v.equals(constPool.getStringInfo(idx)))
                        hit = true;
                } else if (op == Opcode.LDC_W) {
                    size = 3;
                    int idx = (iter.byteAt(pos + 1) << 8) | iter.byteAt(pos + 2);
                    if (constPool.getTag(idx) == ConstPool.CONST_String && v.equals(constPool.getStringInfo(idx)))
                        hit = true;
                }
            }

            if (hit) {
                if (targetOrdinal == -1 || occurrences == targetOrdinal) {
                    matches.add(new int[]{pos, size});
                }
                occurrences++;
            }
        }

        if (matches.isEmpty()) return;
        matches.sort((a, b) -> b[0] - a[0]);

        byte[] prefix = isStatic ? new byte[]{(byte) Opcode.ACONST_NULL} : new byte
                        []{(byte) Opcode.ALOAD_0};
        byte[] invoke = new byte[]{
                (byte) Opcode.INVOKESTATIC,
                (byte) (helperRef >> 8),
                (byte) (helperRef & 0xFF)
        };

        for (int[] m : matches) {
            iter.insertAt(m[0], prefix);
            iter.insertAt(m[0] + prefix.length + m[1], invoke);
        }
        ca.computeMaxStack();
    }

    // ==================== @ModifyArg ====================

    private void applyModifyArg(CtClass targetClass, Class<?> mixinClass, CtMethod ctMethod)
            throws Exception {
        ModifyArg modify = (ModifyArg) ctMethod.getAnnotation(ModifyArg.class);
        CtMethod targetMethod = findMethod(targetClass, modify.method(), modify.descriptor());
        if (targetMethod == null) {
            LOGGER.warn("ModifyArg target not found: {}.{}", targetClass.getName(), modify.method());
            return;
        }

        String target = modify.at().target();
        final int ordinal = modify.at().ordinal();
        final int argIndex = modify.index();
        final int[] count = {0};

        targetMethod.instrument(new ExprEditor() {
            @Override
            public void edit(MethodCall m) throws CannotCompileException {
                String sig = m.getClassName() + "." + m.getMethodName();
                if (sig.equals(target) || m.getMethodName().equals(target)) {
                    if (ordinal == -1 || count[0] == ordinal) {
                        try {
                            m.replace(buildModifyArgCode(mixinClass, ctMethod, m, argIndex));
                        } catch (Exception e) {
                            throw new CannotCompileException(e);
                        }
                    }
                    count[0]++;
                }
            }
        });
        LOGGER.info("ModifyArg applied: index {} in {}.{}", argIndex, targetClass.getName(), targetMethod.getName());
    }

    // ==================== @ModifyArgs ====================

    private void applyModifyArgs(CtClass targetClass, Class<?> mixinClass, CtMethod ctMethod)
            throws Exception {
        ModifyArgs modify = (ModifyArgs) ctMethod.getAnnotation(ModifyArgs.class);
        CtMethod targetMethod = findMethod(targetClass, modify.method(), modify.descriptor());
        if (targetMethod == null) {
            LOGGER.warn("ModifyArgs target not found: {}.{}", targetClass.getName(), modify.method());
            return;
        }

        String target = modify.at().target();
        final int ordinal = modify.at().ordinal();
        final int[] count = {0};

        targetMethod.instrument(new ExprEditor() {
            @Override
            public void edit(MethodCall m) throws CannotCompileException {
                String sig = m.getClassName() + "." + m.getMethodName();
                if (sig.equals(target) || m.getMethodName().equals(target)) {
                    if (ordinal == -1 || count[0] == ordinal) {
                        try {
                            m.replace(buildModifyArgsCode(mixinClass, ctMethod, m));
                        } catch (Exception e) {
                            throw new CannotCompileException(e);
                        }
                    }
                    count[0]++;
                }
            }
        });
        LOGGER.info("ModifyArgs applied: {}.{}", targetClass.getName(), targetMethod.getName());
    }

    // ==================== @ModifyVariable ====================

    private void applyModifyVariable(CtClass targetClass, Class<?> mixinClass, CtMethod ctMethod)
            throws Exception {
        ModifyVariable modify = (ModifyVariable) ctMethod.getAnnotation(ModifyVariable.class);
        CtMethod targetMethod = findMethod(targetClass, modify.method(), modify.descriptor());
        if (targetMethod == null) {
            LOGGER.warn("ModifyVariable target not found: {}.{}", targetClass.getName(), modify.method());
            return;
        }

        boolean isStatic = (targetMethod.getModifiers() & Modifier.STATIC) != 0;
        CtClass[] mixinParams = ctMethod.getParameterTypes();
        CtClass varCtType = ctMethod.getReturnType();
        int index = modify.index();
        String name = modify.name();
        String varRef = name.isEmpty() ? ("$" + index) : name;
        String selfArg = isStatic ? "null" : "$0";

        StringBuilder ptSb = new StringBuilder();
        for (int i = 0; i < mixinParams.length; i++) {
            if (i > 0) ptSb.append(", ");
            ptSb.append(mixinParams[i].getName()).append(".class");
        }

        String code = "{\n"
                + "    Object _mvMi = net.rain.api.mixin.manager.MixinRegistry.getInstance(\""
                + mixinClass.getName() + "\");\n"
                + "    if (_mvMi != null) try {\n"
                + "        Class[] _mvPt = new Class[]{" + ptSb + "};\n"
                + "        java.lang.reflect.Method _mvM = _mvMi.getClass().getMethod(\""
                + ctMethod.getName() + "\", _mvPt);\n"
                + "        Object[] _mvArgs = new Object[]{ " + selfArg + ", "
                + boxPrimitive(varCtType, varRef) + " };\n"
                + "        Object _mvRes = _mvM.invoke(_mvMi, _mvArgs);\n"
                + "        " + varRef + " = " + unboxReturn(varCtType, "_mvRes") + ";\n"
                + "    } catch (Exception _mvE) { _mvE.printStackTrace(); }\n"
                + "}\n";

        String point = "HEAD";
        String invokeTarget = "";
        int invokeOrdinal = -1;
        try {
            java.lang.reflect.Method atMethod = modify.getClass().getMethod("at");
            Object at = atMethod.invoke(modify);
            if (at != null) {
                point = (String) at.getClass().getMethod("value").invoke(at);
                try {
                    invokeTarget = (String) at.getClass().getMethod("target").invoke(at);
                } catch (Exception ignored) {
                }
                try {
                    invokeOrdinal = (int) at.getClass().getMethod("ordinal").invoke(at);
                } catch (Exception ignored) {
                }
            }
        } catch (NoSuchMethodException ignored) {
        }

        final String finalTarget = invokeTarget;
        final int finalOrdinal = invokeOrdinal;
        final String finalCode = code;

        switch (point) {
            case "RETURN":
                targetMethod.insertAfter(code, false);
                break;
            case "TAIL":
                targetMethod.insertAfter(code, true);
                break;
            case "INVOKE":
                {
                    final int[] cnt = {0};
                    targetMethod.instrument(new ExprEditor() {
                        @Override
                        public void edit(MethodCall m) throws CannotCompileException {
                            String sig = m.getClassName() + "." + m.getMethodName();
                            if (sig.equals(finalTarget) || m.getMethodName().equals(finalTarget)) {
                                if (finalOrdinal == -1 || cnt[0] == finalOrdinal) {
                                    try {
                                        m.replace("{ $_ = $proceed($$); " + finalCode + " }");
                                    } catch (Exception e) {
                                        throw new CannotCompileException(e);
                                    }
                                }
                                cnt[0]++;
                            }
                        }
                    });
                    break;
                }
            default:
                targetMethod.insertBefore(code);
        }
        LOGGER.info("ModifyVariable applied: {}.{} (var: {})", targetClass.getName(), targetMethod.getName(), varRef);
    }

    // ==================== @Unique (method) ====================

    private void applyUniqueMethod(CtClass targetClass, Class<?> mixinClass, CtMethod ctMethod)
            throws Exception {
        String methodName = ctMethod.getName();
        CtClass[] paramTypes = ctMethod.getParameterTypes();
        CtClass returnType = ctMethod.getReturnType();

        try {
            targetClass.getDeclaredMethod(methodName, paramTypes);
            Unique unique = (Unique) ctMethod.getAnnotation(Unique.class);
            if (unique != null && !unique.silent()) {
                LOGGER.warn("Unique method already exists: {}.{}", targetClass.getName(), methodName);
            }
            return;
        } catch (NotFoundException ignored) {
        }

        boolean isStatic = (ctMethod.getModifiers() & Modifier.STATIC) != 0;
        CtMethod newMethod = new CtMethod(returnType, methodName, paramTypes, targetClass);
        newMethod.setModifiers(ctMethod.getModifiers());

        StringBuilder body = new StringBuilder("{\n");
        body.append("    Object _mi = net.rain.api.mixin.manager.MixinRegistry.getInstance(\"")
                .append(mixinClass.getName()).append("\");\n");
        body.append("    Class[] _pt = new Class[]{");
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) body.append(", ");
            body.append(paramTypes[i].getName()).append(".class");
        }
        body.append("};\n");
        body.append("    Object[] _args = new Object[]{").append(isStatic ? "null" : "$0");
        for (int i = 0; i < paramTypes.length; i++) {
            body.append(", ").append(boxPrimitive(paramTypes[i], "$" + (i + 1)));
        }
        body.append("};\n");
        body.append("    Object _res = _mi.getClass().getMethod(\"")
                .append(methodName).append("\", _pt).invoke(_mi, _args);\n");
        if (returnType.equals(CtClass.voidType)) {
            body.append("    return;\n");
        } else {
            body.append("    return ").append(unboxReturn(returnType, "_res")).append(";\n");
        }
        body.append("}");

        newMethod.setBody(body.toString());
        targetClass.addMethod(newMethod);
        LOGGER.info("Added unique method: {}.{}", targetClass.getName(), methodName);
    }

    // ==================== @Accessor ====================

    private void applyAccessor(CtClass targetClass, CtMethod ctMethod) throws Exception {
        Accessor accessor = (Accessor) ctMethod.getAnnotation(Accessor.class);
        String fieldName = accessor.value();
        if (fieldName.isEmpty()) {
            String mn = ctMethod.getName();
            if (mn.startsWith("get") || mn.startsWith("set")) {
                fieldName = Character.toLowerCase(mn.charAt(3)) + mn.substring(4);
            } else {
                LOGGER.warn("Cannot infer accessor field name: {}", mn);
                return;
            }
        }
        CtField field = targetClass.getField(fieldName);
        String mn = ctMethod.getName();
        if (mn.startsWith("get")) {
            targetClass.addMethod(CtNewMethod.getter(mn, field));
        } else if (mn.startsWith("set")) {
            targetClass.addMethod(CtNewMethod.setter(mn, field));
        }
        LOGGER.info("Added accessor: {}.{}", targetClass.getName(), mn);
    }

    // ==================== @Invoker ====================

    private void applyInvoker(CtClass targetClass, CtMethod ctMethod) throws Exception {
        Invoker invoker = (Invoker) ctMethod.getAnnotation(Invoker.class);
        String targetMethodName = invoker.value().isEmpty() ? ctMethod.getName() : invoker.value();
        CtClass returnType = ctMethod.getReturnType();
        CtMethod invokerCtMethod = new CtMethod(returnType, ctMethod.getName(),
        ctMethod.getParameterTypes(), targetClass);
        String body = "{ " + (returnType.equals(CtClass.voidType) ? "" : "return ")
                + "this." + targetMethodName + "($$); }";
        invokerCtMethod.setBody(body);
        targetClass.addMethod(invokerCtMethod);
        LOGGER.info("Added invoker: {}.{} -> {}", targetClass.getName(), ctMethod.getName(), targetMethodName);
    }

    // ==================== Code Builders ====================

    private String buildCallbackCode(CtMethod targetMethod, Class<?> mixinClass,
            CtMethod ctInjectMethod, boolean cancellable)
            throws Exception {
        boolean isStatic = (targetMethod.getModifiers() & Modifier.STATIC) != 0;
        CtClass[] mixinParams = ctInjectMethod.getParameterTypes();
        CtClass[] targetParams = targetMethod.getParameterTypes();

        StringBuilder code = new StringBuilder("{\n");
        if (cancellable) {
            code.append("    net.rain.api.mixin.impl.CallbackInfoImpl ci = ")
                    .append("new net.rain.api.mixin.impl.CallbackInfoImpl(\"")
                    .append(ctInjectMethod.getName()).append("\");\n");
        } else {
            code.append("    net.rain.api.mixin.callback.CallbackInfo ci = ")
                    .append("new net.rain.api.mixin.impl.CallbackInfoImpl(\"")
                    .append(ctInjectMethod.getName()).append("\");\n");
        }

        code.append("    Object _mi = net.rain.api.mixin.manager.MixinRegistry.getInstance(\"")
                .append(mixinClass.getName()).append("\");\n");
        code.append("    if (_mi == null) { return");
        if (!targetMethod.getReturnType().equals(CtClass.voidType)) {
            code.append(" ").append(getDefaultValue(targetMethod.getReturnType()));
        }
        code.append("; }\n");

        code.append("    try {\n");
        code.append("        Class[] _pt = new Class[]{");
        for (int i = 0; i < mixinParams.length; i++) {
            if (i > 0) code.append(", ");
            code.append(mixinParams[i].getName()).append(".class");
        }
        code.append("};\n");

        code.append("        java.lang.reflect.Method _m = _mi.getClass().getMethod(\"")
                .append(ctInjectMethod.getName()).append("\", _pt);\n");

        // args[0] = this/null, args[1..n-1] = original params, args[n] = ci
        code.append("        Object[] _args = new Object[]{").append(isStatic ? "null" : "$0");
        for (int i = 1; i < mixinParams.length; i++) {
            code.append(", ");
            if (i == mixinParams.length - 1) {
                code.append("ci");
            } else {
                int pIdx = i;
                CtClass pType = targetParams[i - 1];
                code.append(boxPrimitive(pType, "$" + pIdx));
            }
        }
        code.append("};\n");

        code.append("        _m.invoke(_mi, _args);\n");
        code.append("    } catch (NoSuchMethodException _e) {\n");
        code.append("        System.err.println(\"[Mixin] Method not found: \" + _e.getMessage());\n");
        code.append("    } catch (Exception _e) {\n");
        code.append("        System.err.println(\"[Mixin] Invocation failed: \" + _e.getMessage());\n");
        code.append("        _e.printStackTrace();\n");
        code.append("    }\n");

        if (cancellable) {
            code.append("    if (((net.rain.api.mixin.impl.CallbackInfoImpl) ci).isCancelled()) {\n");
            code.append("        return");
            if (!targetMethod.getReturnType().equals(CtClass.voidType)) {
                code.append(" ").append(getDefaultValue(targetMethod.getReturnType()));
            }
            code.append(";\n    }\n");
        }

        code.append("}\n");
        return code.toString();
    }

    /**
     * Builds injection code for RETURN injection points. Mixin method signature: (TargetClass self,
     * CallbackInfo[Returnable] ci) args: [this/null, cir/ci]
     */
    private String buildReturnCallbackCode(CtMethod targetMethod, Class<?> mixinClass,
            CtMethod ctInjectMethod, boolean cancellable, boolean hasReturnValue)
            throws Exception {
        boolean isStatic = (targetMethod.getModifiers() & Modifier.STATIC) != 0;
        CtClass[] mixinParams = ctInjectMethod.getParameterTypes();
        CtClass returnType = targetMethod.getReturnType();

        StringBuilder code = new StringBuilder("{\n");

        if (hasReturnValue) {
            code.append("    net.rain.api.mixin.impl.CallbackInfoReturnableImpl cir = ")
                    .append("new net.rain.api.mixin.impl.CallbackInfoReturnableImpl(\"")
                    .append(ctInjectMethod.getName()).append("\", ")
                    .append(boxPrimitive(returnType, "$_")).append(");\n");

            code.append("    Object _mi = net.rain.api.mixin.manager.MixinRegistry.getInstance(\"")
                    .append(mixinClass.getName()).append("\");\n");
            code.append("    if (_mi != null) try {\n");
            code.append("        Class[] _pt = new Class[]{");
            for (int i = 0; i < mixinParams.length; i++) {
                if (i > 0) code.append(", ");
                code.append(mixinParams[i].getName()).append(".class");
            }
            code.append("};\n");
            code.append("        java.lang.reflect.Method _m = _mi.getClass().getMethod(\"")
                    .append(ctInjectMethod.getName()).append("\", _pt);\n");
            code.append("        Object[] _args = new Object[]{ ")
                    .append(isStatic ? "null" : "$0").append(", cir };\n");
            code.append("        _m.invoke(_mi, _args);\n");
            code.append("    } catch (Exception _e) { _e.printStackTrace(); }\n");

            if (cancellable) {
                code.append("    if (cir.isCancelled()) {\n");
                code.append("        $_ = ").append(unboxReturn(returnType, "cir.getReturnValue()")).append(";\n");
                code.append("    }\n");
            }
        } else {
            code.append("    net.rain.api.mixin.callback.CallbackInfo ci = ")
                    .append("new net.rain.api.mixin.impl.CallbackInfoImpl(\"")
                    .append(ctInjectMethod.getName()).append("\");\n");
            code.append("    Object _mi = net.rain.api.mixin.manager.MixinRegistry.getInstance(\"")
                    .append(mixinClass.getName()).append("\");\n");
            code.append("    if (_mi != null) try {\n");
            code.append("        Class[] _pt = new Class[]{");
            for (int i = 0; i < mixinParams.length; i++) {
                if (i > 0) code.append(", ");
                code.append(mixinParams[i].getName()).append(".class");
            }
            code.append("};\n");
            code.append("        java.lang.reflect.Method _m = _mi.getClass().getMethod(\"")
                    .append(ctInjectMethod.getName()).append("\", _pt);\n");
            code.append("        Object[] _args = new Object[]{ ")
                    .append(isStatic ? "null" : "$0").append(", ci };\n");
            code.append("        _m.invoke(_mi, _args);\n");
            code.append("    } catch (Exception _e) { _e.printStackTrace(); }\n");
        }

        code.append("}\n");
        return code.toString();
    }

    /**
     * Builds redirect code. Mixin method signature: (TargetClass self, CalleeParam1, CalleeParam2,
     * ...) -> ReturnType In ExprEditor context: $0 = this of enclosing method, $1,$2,... = callee
     * args. args: [$0 (this of enclosing), callee arg1, arg2, ...]
     */
    private String buildRedirectCode(Class<
                    ?> mixinClass, CtMethod ctRedirectMethod, MethodCall methodCall)
            throws Exception {
        CtClass[] calleeParams;
        CtClass calleeReturn;
        try {
            calleeParams = methodCall.getMethod().getParameterTypes();
            calleeReturn = methodCall.getMethod().getReturnType();
        } catch (NotFoundException e) {
            LOGGER.warn("Cannot resolve redirected method params, falling back to $proceed", e);
            return "{ $_ = $proceed($$); }";
        }

        CtClass[] mixinParams = ctRedirectMethod.getParameterTypes();

        StringBuilder code = new StringBuilder("{\n");
        code.append("    Object _mi = net.rain.api.mixin.manager.MixinRegistry.getInstance(\"")
                .append(mixinClass.getName()).append("\");\n");
        code.append("    if (_mi == null) { $_ = $proceed($$); }\n");
        code.append("    else try {\n");
        code.append("        Class[] _pt = new Class[]{");
        for (int i = 0; i < mixinParams.length; i++) {
            if (i > 0) code.append(", ");
            code.append(mixinParams[i].getName()).append(".class");
        }
        code.append("};\n");
        code.append("        java.lang.reflect.Method _m = _mi.getClass().getMethod(\"")
                .append(ctRedirectMethod.getName()).append("\", _pt);\n");

        // args: [$0 (this of enclosing), callee arg1, arg2, ...]
        code.append("        Object[] _args = new Object[]{ $0");
        for (int i = 0; i < calleeParams.length; i++) {
            code.append(", ").append(boxPrimitive(calleeParams[i], "$" + (i + 1)));
        }
        code.append("};\n");

        code.append("        Object _res = _m.invoke(_mi, _args);\n");
        if (calleeReturn.equals(CtClass.voidType)) {
            code.append("        $_ = null;\n");
        } else {
            code.append("        $_ = ").append(unboxReturn(calleeReturn, "_res")).append(";\n");
        }
        code.append("    } catch (Exception _e) { _e.printStackTrace(); $_ = $proceed($$); }\n");
        code.append("}\n");
        return code.toString();
    }

    /**
     * Builds ModifyArg code. Mixin method signature: (TargetClass self, ArgType original) ->
     * ArgType In ExprEditor context: $0 = this of enclosing method, $1,$2,... = callee args. args:
     * [$0 (this), originalArg]
     */
    private String buildModifyArgCode(Class<?> mixinClass, CtMethod ctMethod,
            MethodCall methodCall, int argIndex)
            throws Exception {
        CtClass[] mixinParams = ctMethod.getParameterTypes();

        StringBuilder code = new StringBuilder("{\n");
        code.append("    Object _mi = net.rain.api.mixin.manager.MixinRegistry.getInstance(\"")
                .append(mixinClass.getName()).append("\");\n");
        code.append("    try {\n");
        code.append("        Class[] _pt = new Class[]{");
        for (int i = 0; i < mixinParams.length; i++) {
            if (i > 0) code.append(", ");
            code.append(mixinParams[i].getName()).append(".class");
        }
        code.append("};\n");
        code.append("        java.lang.reflect.Method _m = _mi.getClass().getMethod(\"")
                .append(ctMethod.getName()).append("\", _pt);\n");
        code.append("        Object[] _args = new Object[]{ $0, $").append(argIndex + 1).append(" };\n");
        code.append("        Object _newArg = _m.invoke(_mi, _args);\n");

        CtClass[] calleeParams;
        try {
            calleeParams = methodCall.getMethod().getParameterTypes();
        } catch (NotFoundException e) {
            code.append("        $_ = $proceed($$);\n");
            code.append("    } catch (Exception _e) { _e.printStackTrace(); $_ = $proceed($$); }\n");
            code.append("}\n");
            return code.toString();
        }

        code.append("        Object[] _newCallArgs = new Object[]{");
        for (int i = 0; i < calleeParams.length; i++) {
            if (i > 0) code.append(", ");
            if (i == argIndex) {
                code.append(unboxReturn(calleeParams[i], "_newArg"));
            } else {
                code.append("$").append(i + 1);
            }
        }
        code.append("};\n");

        CtClass calleeReturn;
        try {
            calleeReturn = methodCall.getMethod().getReturnType();
        } catch (NotFoundException e) {
            calleeReturn = CtClass.voidType;
        }

        if (calleeReturn.equals(CtClass.voidType)) {
            code.append("        $proceed(_newCallArgs);\n");
        } else {
            code.append("        $_ = $proceed(_newCallArgs);\n");
        }
        code.append("    } catch (Exception _e) { _e.printStackTrace(); $_ = $proceed($$); }\n");
        code.append("}\n");
        return code.toString();
    }

    /**
     * Builds ModifyArgs code. Mixin method signature: (TargetClass self, Args args) args: [$0
     * (this), argsObj]
     */
    private String buildModifyArgsCode(Class<
                    ?> mixinClass, CtMethod ctMethod, MethodCall methodCall)
            throws Exception {
        CtClass[] mixinParams = ctMethod.getParameterTypes();

        StringBuilder code = new StringBuilder("{\n");
        code.append("    net.rain.api.mixin.impl.ArgsImpl _argsObj = new net.rain.api.mixin.impl.ArgsImpl($$);\n");
        code.append("    Object _mi = net.rain.api.mixin.manager.MixinRegistry.getInstance(\"")
                .append(mixinClass.getName()).append("\");\n");
        code.append("    try {\n");
        code.append("        Class[] _pt = new Class[]{");
        for (int i = 0; i < mixinParams.length; i++) {
            if (i > 0) code.append(", ");
            code.append(mixinParams[i].getName()).append(".class");
        }
        code.append("};\n");
        code.append("        java.lang.reflect.Method _m = _mi.getClass().getMethod(\"")
                .append(ctMethod.getName()).append("\", _pt);\n");
        code.append("        _m.invoke(_mi, new Object[]{ $0, _argsObj });\n");
        code.append("        $_ = $proceed(_argsObj.getArgs());\n");
        code.append("    } catch (Exception _e) { _e.printStackTrace(); $_ = $proceed($$); }\n");
        code.append("}\n");
        return code.toString();
    }

    // ==================== Type Helpers ====================

    private String boxPrimitive(CtClass type, String varName) {
        if (!type.isPrimitive()) return varName;
        try {
            if (type.equals(CtClass.intType)) return "Integer.valueOf(" + varName + ")";
            if (type.equals(CtClass.longType)) return "Long.valueOf(" + varName + ")";
            if (type.equals(CtClass.floatType)) return "Float.valueOf(" + varName + ")";
            if (type.equals(CtClass.doubleType)) return "Double.valueOf(" + varName + ")";
            if (type.equals(CtClass.booleanType)) return "Boolean.valueOf(" + varName + ")";
            if (type.equals(CtClass.byteType)) return "Byte.valueOf(" + varName + ")";
            if (type.equals(CtClass.charType)) return "Character.valueOf(" + varName + ")";
            if (type.equals(CtClass.shortType)) return "Short.valueOf(" + varName + ")";
        } catch (Exception e) {
            LOGGER.error("Error boxing type", e);
        }
        return varName;
    }

    private String unboxReturn(CtClass type, String varName) {
        if (!type.isPrimitive()) return "((" + type.getName() + ")" + varName + ")";
        try {
            if (type.equals(CtClass.intType)) return "((Integer)" + varName + ").intValue()";
            if (type.equals(CtClass.longType)) return "((Long)" + varName + ").longValue()";
            if (type.equals(CtClass.floatType)) return "((Float)" + varName + ").floatValue()";
            if (type.equals(CtClass.doubleType)) return "((Double)" + varName + ").doubleValue()";
            if (type.equals(CtClass.booleanType))
                return "((Boolean)" + varName + ").booleanValue()";
            if (type.equals(CtClass.byteType)) return "((Byte)" + varName + ").byteValue()";
            if (type.equals(CtClass.charType)) return "((Character)" + varName + ").charValue()";
            if (type.equals(CtClass.shortType)) return "((Short)" + varName + ").shortValue()";
        } catch (Exception e) {
            LOGGER.error("Error unboxing type", e);
        }
        return "((" + type.getName() + ")" + varName + ")";
    }

    private String getDefaultValue(CtClass type) {
        if (!type.isPrimitive()) return "null";
        try {
            if (type.equals(CtClass.intType)) return "0";
            if (type.equals(CtClass.longType)) return "0L";
            if (type.equals(CtClass.floatType)) return "0.0f";
            if (type.equals(CtClass.doubleType)) return "0.0";
            if (type.equals(CtClass.booleanType)) return "false";
            if (type.equals(CtClass.byteType)) return "(byte)0";
            if (type.equals(CtClass.charType)) return "(char)0";
            if (type.equals(CtClass.shortType)) return "(short)0";
        } catch (Exception e) {
            LOGGER.error("Error getting default value", e);
        }
        return "null";
    }

    private String getTypeDescriptor(Class<?> type) {
        if (type == int.class) return "I";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        if (type == long.class) return "J";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == void.class) return "V";
        return "L" + type.getName().replace('.', '/') + ";";
    }

    // ==================== findMethod with MCP->SRG mapping support ====================

    private CtMethod findMethod(CtClass targetClass, String methodName, String descriptor) {
        // Try to find SRG name from MCP name (for Forge 1.20.1)
        String srgName = MinecraftHelper.findSrgMethodName(targetClass.getName(), methodName);

        List<String> candidates = new ArrayList<>();
        if (srgName != null && !srgName.equals(methodName)) {
            candidates.add(srgName);
        }
        candidates.add(methodName);

        for (String name : candidates) {
            try {
                if (descriptor.isEmpty()) {
                    return targetClass.getDeclaredMethod(name);
                }
                for (CtMethod m : targetClass.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getSignature().equals(descriptor)) {
                        return m;
                    }
                }
            } catch (NotFoundException ignored) {
            }
        }
        return null;
    }

    // ==================== Utility ====================

    private void sortMixinsByPriority(List<Class<?>> mixins) {
        mixins.sort((a, b) -> {
            try {
                IMixin ma = (IMixin) MixinRegistry.getInstance(a.getName());
                IMixin mb = (IMixin) MixinRegistry.getInstance(b.getName());
                if (ma == null || mb == null) return 0;
                return Integer.compare(mb.getPriority(), ma.getPriority());
            } catch (Exception e) {
                return 0;
            }
        });
    }

    private boolean isPseudoMixin(Class<?> mixinClass) {
        return mixinClass.isAnnotationPresent(Pseudo.class);
    }

    private boolean handlePseudoMixin(CtClass targetClass, Class<?> mixinClass) {
        if (targetClass == null) {
            LOGGER.info("Skipping pseudo mixin (target class absent): {}", mixinClass.getName());
            return false;
        }
        return true;
    }

    private boolean isExcludedPackage(String className) {
        return className.startsWith("net.rain.api.mixin.") ||
                className.startsWith("java.") ||
                className.startsWith("javax.") ||
                className.startsWith("sun.") ||
                className.startsWith("jdk.") ||
                className.startsWith("javassist.") ||
                className.startsWith("org.objectweb.asm.");
    }
}
