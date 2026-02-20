package net.rain.api.coremod;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Map;

public interface ICoreModLoadingPlugin {
    String[] getASMTransformerClass();

    void injectData(Map<String, Object> var1);

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE})
    @interface Name {
        String value() default "";
    }
}