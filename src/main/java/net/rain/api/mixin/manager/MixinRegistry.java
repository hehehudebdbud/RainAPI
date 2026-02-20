package net.rain.api.mixin.manager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MixinRegistry {
    private static final Map<String, Object> INSTANCES = new ConcurrentHashMap<>();
    
    public static void registerInstance(String className, Object instance) {
        INSTANCES.put(className, instance);
    }
    
    public static Object getInstance(String className) {
        return INSTANCES.get(className);
    }
    
    public static void clear() {
        INSTANCES.clear();
    }
}
