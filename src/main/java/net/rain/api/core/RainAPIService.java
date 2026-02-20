package net.rain.api.core;

import cpw.mods.modlauncher.LaunchPluginHandler;
import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.TransformTargetLabel;
import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.IncompatibleEnvironmentException;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import net.rain.api.coremod.manager.CoreModManager;
import net.rain.api.mixin.transformer.MixinTransformer;
import net.rain.api.coremod.transformer.*;
import org.spongepowered.asm.launch.*;
import org.jetbrains.annotations.NotNull;
import sun.misc.Unsafe;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import net.minecraftforge.fml.loading.FMLPaths;
import org.spongepowered.asm.mixin.Mixins;
import java.util.*;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.module.Configuration;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.net.*;
import java.util.concurrent.ConcurrentHashMap;
import net.rain.api.mixin.loader.MixinLoader;

// 操你妈的傻逼coremod
public class RainAPIService implements ITransformationService {
    private static final Logger LOGGER = LogManager.getLogger("RainAPIService");

    static {
        LaunchPluginHandler handler = UnsafeHelper.getFieldValue(Launcher.INSTANCE, "launchPlugins", LaunchPluginHandler.class);
        Map<String, ILaunchPluginService> plugins = (Map<String,ILaunchPluginService>) UnsafeHelper.getFieldValue(handler, "plugins", Map.class);
        Map<String, ILaunchPluginService> newMap = new ConcurrentHashMap<>();
        newMap.put("AccessTransformer", new AccessTransformer());
        newMap.put("MixinTransformer", new MixinTransformer());
        if (plugins != null)
            for (String name : plugins.keySet())
                newMap.put(name, plugins.get(name));
        UnsafeHelper.setFieldValue(handler, "plugins", newMap);
        System.out.println("成功注册我的ILaunchPluginService");
        CoreModManager.loadCoreMods();
        MixinLoader.init();
        MixinLoader.loadMixinsFromRainJava();
    }

    @Override
    public @NotNull String name() {
        return "RainAPIService";
    }

    @Override
    public void initialize(IEnvironment environment) {
    }

    @Override
    public void onLoad(IEnvironment env, Set<String> otherServices) throws IncompatibleEnvironmentException {
    }

    @Override
    public @NotNull List<ITransformer> transformers() {
        System.out.println("返回了RainClassTransformer");
        return List.of(new RainClassTransformer());
    }
}
