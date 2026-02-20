package net.rain.api.mixin.manager;

import net.rain.api.mixin.IMixin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MixinManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(MixinManager.class);

    private static final Map<String, MixinMetadata> MIXIN_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, List<String>> TARGET_TO_MIXINS = new ConcurrentHashMap<>();
    private static final Map<String, Class<?>> LOADED_MIXINS = new ConcurrentHashMap<>();

    
    private static final Set<String> TRANSFORMED_CLASSES = ConcurrentHashMap.newKeySet();

    public static class MixinMetadata {
        public final String className;
        public final byte[] bytecode;
        public final Path sourceFile;
        public String targetClass;

        public MixinMetadata(String className, byte[] bytecode, Path sourceFile) {
            this.className = className;
            this.bytecode = bytecode;
            this.sourceFile = sourceFile;
        }
    }

    public static void cacheMixinBytecode(String className, byte[] bytecode, Path sourceFile) {
        MIXIN_CACHE.put(className, new MixinMetadata(className, bytecode, sourceFile));
    }

    public static MixinMetadata getMetadata(String className) {
        return MIXIN_CACHE.get(className);
    }

    public static Class<
                    ?> loadAndRegisterMixin(String mixinClassName, ClassLoader gameClassLoader) {
        Class<?> cached = LOADED_MIXINS.get(mixinClassName);
        if (cached != null) return cached;

        MixinMetadata metadata = MIXIN_CACHE.get(mixinClassName);
        if (metadata == null) return null;

        try {
            
            net.rain.api.core.java.DynamicClassLoader classLoader = new net.rain.api.core.java.DynamicClassLoader(gameClassLoader);

            classLoader.addCompiledClass(metadata.className, metadata.bytecode);
            Class<?> mixinClass = classLoader.loadClass(metadata.className);

            if (!IMixin.class.isAssignableFrom(mixinClass)) {
                LOGGER.warn("Class {} does not implement IMixin", mixinClassName);
                return null;
            }

            IMixin mixin = (IMixin) mixinClass.getDeclaredConstructor().newInstance();
            if (!mixin.isEnabled()) return null;

            MixinRegistry.registerInstance(mixinClassName, mixin);

            String targetClass = mixin.getTargetClass();
            metadata.targetClass = targetClass;

            TARGET_TO_MIXINS.computeIfAbsent(targetClass, k -> new ArrayList<>()).add(mixinClassName);
            LOADED_MIXINS.put(mixinClassName, mixinClass);

            LOGGER.info("Loaded and registered mixin: {} -> {}", mixinClassName, targetClass);
            return mixinClass;

        } catch (Exception e) {
            LOGGER.error("Failed to load mixin: {}", mixinClassName, e);
            return null;
        }
    }

    public static boolean hasMixins(String className, ClassLoader gameClassLoader) {
        if (TARGET_TO_MIXINS.containsKey(className)) return true;

        for (String mixinClassName : MIXIN_CACHE.keySet()) {
            if (!LOADED_MIXINS.containsKey(mixinClassName)) {
                Class<?> loaded = loadAndRegisterMixin(mixinClassName, gameClassLoader);
                if (loaded != null) {
                    MixinMetadata metadata = MIXIN_CACHE.get(mixinClassName);
                    if (className.equals(metadata.targetClass)) return true;
                }
            }
        }

        return TARGET_TO_MIXINS.containsKey(className);
    }

    public static List<Class<?>> getMixinsFor(String className, ClassLoader gameClassLoader) {
        hasMixins(className, gameClassLoader);

        List<String> mixinNames = TARGET_TO_MIXINS.getOrDefault(className, Collections.emptyList());
        List<Class<?>> mixinClasses = new ArrayList<>();
        for (String mixinName : mixinNames) {
            Class<?> mixinClass = LOADED_MIXINS.get(mixinName);
            if (mixinClass != null) mixinClasses.add(mixinClass);
        }
        return mixinClasses;
    }

    /** Returns true if this class has not been transformed yet (prevents double-processing). */
    public static boolean markTransforming(String className) {
        return TRANSFORMED_CLASSES.add(className);
    }

    public static int getCachedMixinCount() {
        return MIXIN_CACHE.size();
    }

    public static int getLoadedMixinCount() {
        return LOADED_MIXINS.size();
    }

    public static int getTotalMixinCount() {
        return TARGET_TO_MIXINS.values().stream().mapToInt(List::size).sum();
    }

    public static void clearAll() {
        MIXIN_CACHE.clear();
        TARGET_TO_MIXINS.clear();
        LOADED_MIXINS.clear();
        TRANSFORMED_CLASSES.clear();
        MixinRegistry.clear();
        LOGGER.info("All mixin caches cleared");
    }

    public static void printDebugInfo() {
        LOGGER.info("========================================");
        LOGGER.info("Mixin system status:");
        LOGGER.info("  Cached:  {}", getCachedMixinCount());
        LOGGER.info("  Loaded:  {}", getLoadedMixinCount());
        LOGGER.info("  Targets: {}", TARGET_TO_MIXINS.size());
        LOGGER.info("========================================");
        for (Map.Entry<String, List<String>> entry : TARGET_TO_MIXINS.entrySet()) {
            LOGGER.info("  {} -> {}", entry.getKey(), entry.getValue());
        }
    }
}
