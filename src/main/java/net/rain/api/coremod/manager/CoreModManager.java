package net.rain.api.coremod.manager;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.loading.FMLPaths;
import net.rain.api.coremod.ICoreClassTransformer;
import net.rain.api.coremod.ICoreModLoadingPlugin;
import net.rain.api.core.java.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.objectweb.asm.tree.ClassNode;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.eclipse.jdt.internal.compiler.tool.EclipseCompiler;
import java.io.File;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

public class CoreModManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(CoreModManager.class);
    private static final List<ICoreClassTransformer> transformers = new ArrayList<>();
    private static final Map<String, ICoreModLoadingPlugin> plugins = new HashMap<>();
    private static final Gson GSON = new Gson();
    private static JavaSourceCompiler compiler;
    private static DynamicClassLoader classLoader;

    public static boolean hasTransformers() {
        return !transformers.isEmpty();
    }

    public static void loadCoreMods() {
        
        ClassLoader parentLoader = CoreModManager.class.getClassLoader();
        classLoader = new DynamicClassLoader(parentLoader);
        
        if (!initializeCompiler()) {
            LOGGER.error("Failed to initialize Java compiler, CoreMod loading disabled");
            return;
        }

        
        try {
            Class<?> coreClazz = parentLoader.loadClass("net.rain.api.coremod.ICoreModLoadingPlugin");
            Class<?> transformerClazz = parentLoader.loadClass("net.rain.api.coremod.ICoreClassTransformer");
            LOGGER.info("Successfully verified CoreMod interfaces");
        } catch (ClassNotFoundException e) {
            LOGGER.error("Failed to load CoreMod interfaces", e);
            return;
        }

        Path gameDir = FMLPaths.GAMEDIR.get();
        File coreModDir = gameDir.resolve("RainJava").resolve("coremod").toFile();
        
        if (!coreModDir.exists()) {
            LOGGER.info("CoreMod directory not found, creating: {}", coreModDir);
            coreModDir.mkdirs();
            return;
        }
        
        if (!coreModDir.isDirectory()) {
            LOGGER.warn("CoreMod path exists but is not a directory: {}", coreModDir);
            return;
        }
        
        File coreModJson = new File(coreModDir, "coremod.json");
        if (!coreModJson.exists()) {
            LOGGER.info("No coremod.json found in: {}", coreModDir);
            return;
        }
        
        loadCoreModsFromJson(coreModJson, coreModDir);
        
        LOGGER.info("Loaded {} coremod plugins with {} transformers", 
            plugins.size(), transformers.size());
    }

    private static boolean initializeCompiler() {
        try {
            //不使用java系统编译器，以避免系统编译器和ecj不同
            JavaCompiler systemCompiler = null;
            
            if (systemCompiler == null) {
                try {
                    systemCompiler = new EclipseCompiler();
                    LOGGER.info("Using Eclipse JDT compiler");
                } catch (Exception e) {
                    LOGGER.error("Failed to initialize Eclipse compiler", e);
                    return false;
                }
            } else {
                LOGGER.info("Using system Java compiler");
            }
            
            compiler = new JavaSourceCompiler(systemCompiler);
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to initialize compiler", e);
            return false;
        }
    }
    
    private static void loadCoreModsFromJson(File jsonFile, File baseDir) {
        try (FileReader reader = new FileReader(jsonFile)) {
            JsonObject json = GSON.fromJson(reader, JsonObject.class);
            
            if (!json.has("plugins")) {
                LOGGER.warn("No 'plugins' field found in: {}", jsonFile);
                return;
            }
            
            String pluginClassName = json.get("plugins").getAsString();
            LOGGER.info("Loading coremod plugin: {}", pluginClassName);
            loadCoreModPlugin(pluginClassName, baseDir);
            
        } catch (Exception e) {
            LOGGER.error("Failed to load coremod.json from: {}", jsonFile, e);
        }
    }
    
    private static void loadCoreModPlugin(String pluginClassName, File baseDir) {
        try {
            
            List<Path> javaFiles = new ArrayList<>();
            
            try (Stream<Path> paths = Files.walk(baseDir.toPath())) {
                paths.filter(path -> path.toString().endsWith(".java"))
                     .forEach(javaFiles::add);
            }
            
            if (javaFiles.isEmpty()) {
                LOGGER.warn("No Java files found in: {}", baseDir);
                return;
            }
            
            LOGGER.info("Found {} Java files to compile", javaFiles.size());
            
            
            Map<String, Class<?>> compiledClasses = new HashMap<>();
            
            for (Path javaFile : javaFiles) {
                try {
                    LOGGER.info("Compiling: {}", javaFile.getFileName());
                    
                    
                    CompiledClass compiled = compiler.compile(javaFile);
                    
                    
                    classLoader.addCompiledClass(compiled.className, compiled.bytecode);
                    
                    
                    Class<?> clazz = classLoader.loadClass(compiled.className);
                    
                    
                    compiledClasses.put(compiled.className, clazz);
                    LOGGER.info("Successfully compiled and loaded: {}", compiled.className);
                    
                } catch (Exception e) {
                    LOGGER.error("Failed to compile {}: {}", javaFile.getFileName(), e.getMessage(), e);
                }
            }
            
            
            Class<?> pluginClass = compiledClasses.get(pluginClassName);
            if (pluginClass == null) {
                LOGGER.error("Plugin class not found: {}", pluginClassName);
                LOGGER.error("Available classes: {}", compiledClasses.keySet());
                return;
            }
            
            LOGGER.info("Successfully loaded plugin class: {}", pluginClassName);
            
            
            ICoreModLoadingPlugin plugin = (ICoreModLoadingPlugin) pluginClass
                .getDeclaredConstructor()
                .newInstance();
            
            String pluginName = getPluginName(pluginClass);
            plugins.put(pluginName, plugin);
            
            
            Map<String, Object> data = new HashMap<>();
            data.put("mcLocation", FMLPaths.GAMEDIR.get().toFile());
            data.put("coremodLocation", baseDir);
            data.put("coremodList", new ArrayList<>(plugins.keySet()));
            plugin.injectData(data);
            
            
            String[] transformerClasses = plugin.getASMTransformerClass();
            if (transformerClasses != null) {
                for (String className : transformerClasses) {
                    try {
                        Class<?> transformerClass = classLoader.loadClass(className);
                        ICoreClassTransformer transformer = (ICoreClassTransformer) 
                            transformerClass.getDeclaredConstructor().newInstance();
                        transformers.add(transformer);
                        LOGGER.info("Registered transformer: {}", className);
                    } catch (Exception e) {
                        LOGGER.error("Failed to load transformer {}: {}", className, e.getMessage(), e);
                    }
                }
            }
            
            LOGGER.info("Loaded coremod: {}", pluginName);
            
        } catch (Exception e) {
            LOGGER.error("Failed to load coremod plugin: {}", pluginClassName, e);
            e.printStackTrace();
        }
    }
    
    private static String getPluginName(Class<?> pluginClass) {
        ICoreModLoadingPlugin.Name nameAnnotation = 
            pluginClass.getAnnotation(ICoreModLoadingPlugin.Name.class);
        if (nameAnnotation != null && !nameAnnotation.value().isEmpty()) {
            return nameAnnotation.value();
        }
        return pluginClass.getSimpleName();
    }
    
    public static ClassNode transformClassNode(String className, ClassNode input) {
        ClassNode result = input;
        
        for (ICoreClassTransformer transformer : transformers) {
            try {
                ClassNode transformed = transformer.transform(className, result);
                if (transformed != null) {
                    result = transformed;
                }
            } catch (Exception e) {
                LOGGER.error("Transformer {} failed for {}", 
                    transformer.getClass().getName(), className, e);
            }
        }
        
        return result;
    }
    
    public static List<ICoreClassTransformer> getTransformers() {
        return Collections.unmodifiableList(transformers);
    }
}