package net.rain.api.mixin.loader;

import net.rain.api.mixin.IMixin;
import net.rain.api.mixin.manager.MixinManager;
import net.rain.api.core.java.*;
import net.rain.api.mixin.transformer.MixinTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.eclipse.jdt.internal.compiler.tool.EclipseCompiler;
import java.util.*;
import java.util.stream.Stream;

public class MixinLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(MixinLoader.class);
    private static boolean initialized = false;
    private static JavaSourceCompiler jsc;
    
    public static void init() {
        if (initialized) return;
        
        try {
            initJSC();
            initialized = true;
        } catch (Exception e) {
            LOGGER.error("Failed to initialize Mixin system", e);
            throw new RuntimeException(e);
        }
    }
    
    public static void loadMixinsFromRainJava() {
        try {
            Path gameDir = Paths.get(".").toAbsolutePath().normalize();
            Path rainJavaDir = gameDir.resolve("RainJava");
            Path mixinsDir = rainJavaDir.resolve("mixins");
            
            if (!Files.exists(mixinsDir)) {
                LOGGER.info("Mixins directory does not exist: {}", mixinsDir);
                LOGGER.info("Creating directory: {}", mixinsDir);
                Files.createDirectories(mixinsDir);
                return;
            }
            
            compileMixins(mixinsDir);
            
        } catch (Exception e) {
            LOGGER.error("Failed to load mixins from RainJava directory", e);
        }
    }
    
    private static void initJSC() {
        //不使用java系统编译器，以避免系统编译器和ecj不同
        JavaCompiler systemCompiler = null;
        if (systemCompiler == null) {
            try {
                systemCompiler = new EclipseCompiler();
                LOGGER.info("Using Eclipse JDT compiler");
            } catch (Exception e) {
                LOGGER.error("Failed to initialize Eclipse compiler", e);
                return;
            }
        } else {
            LOGGER.info("Using system Java compiler");
        }
        jsc = new JavaSourceCompiler(systemCompiler);
        LOGGER.info("Compiler initialized with full classpath");
    }
    
    private static void compileMixins(Path mixinsDir) {
        if (!Files.exists(mixinsDir)) {
            LOGGER.info("Mixins directory does not exist: {}", mixinsDir);
            return;
        }
        
        List<Path> javaFiles = new ArrayList<>();
        
        // 扫描所有.java文件
        try (Stream<Path> paths = Files.walk(mixinsDir)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                 .forEach(javaFiles::add);
        } catch (Exception e) {
            LOGGER.error("Failed to scan mixins directory", e);
            return;
        }
        
        if (javaFiles.isEmpty()) {
            LOGGER.info("No mixin source files found in {}", mixinsDir);
            return;
        }
        
        LOGGER.info("Found {} mixin source file(s)", javaFiles.size());
        
        int successCount = 0;
        int failCount = 0;
        
        for (Path file : javaFiles) {
            try {
                Path absolutePath = resolveFilePath(file);
                
                CompiledClass compiled = jsc.compileFileWithTransform(absolutePath);
                
                MixinManager.cacheMixinBytecode(compiled.className, compiled.bytecode, file);
                successCount++;
                
                LOGGER.info("  ✓ Compiled and cached: {}", compiled.className);
                
            } catch (Exception e) {
                failCount++;
                LOGGER.error("  ✗ Failed to compile: {}", file.getFileName(), e);
                if (e.getMessage() != null && e.getMessage().contains("cannot find symbol")) {
                    LOGGER.error("     Hint: Make sure all imported classes are available in the game");
                }
            }
        }
        
    }
    
    public static boolean isInitialized() {
        return initialized;
    }
    
    private static Path resolveFilePath(Path file) {
        Path absolutePath = file.toAbsolutePath().normalize();
        if (Files.exists(absolutePath)) {
            return absolutePath;
        }

        try {
            Path gameDir = Paths.get(".").toAbsolutePath().normalize();
            Path relativePath = gameDir.resolve(file).normalize();
            if (Files.exists(relativePath)) {
                return relativePath;
            }
        } catch (Exception ignored) {
        }

        if (!file.isAbsolute()) {
            try {
                Path currentDir = Paths.get("").toAbsolutePath();
                Path resolved = currentDir.resolve(file).normalize();
                if (Files.exists(resolved)) {
                    return resolved;
                }
            } catch (Exception ignored) {
            }
        }

        return absolutePath;
    }
}
