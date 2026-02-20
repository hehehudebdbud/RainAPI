package net.rain.api.mixin;

public interface IMixin {
    String getTargetClass();
    
    default int getPriority() {
        return 1000;
    }
    
    default boolean isEnabled() {
        return true;
    }
}