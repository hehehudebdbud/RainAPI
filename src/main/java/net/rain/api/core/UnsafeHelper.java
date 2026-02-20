package net.rain.api.core;

import sun.misc.Unsafe;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

public final class UnsafeHelper {

    private static final Unsafe UNSAFE;
    private static final MethodHandles.Lookup LOOKUP;

    static {
        UNSAFE = initUnsafe();
        LOOKUP = initLookup();
    }

    private static Unsafe initUnsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Unsafe", e);
        }
    }

    private static MethodHandles.Lookup initLookup() {
        try {
            // 使用 Unsafe 获取 IMPL_LOOKUP 字段
            Field field = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            long offset = UNSAFE.staticFieldOffset(field);
            return (MethodHandles.Lookup) UNSAFE.getObject(
                            UNSAFE.staticFieldBase(field),
                            offset
                    );
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Lookup", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T getFieldValue(Field field, Object target, Class<T> type) {
        try {
            long offset = getFieldOffset(field);
            Object base = Modifier.isStatic(field.getModifiers())
                    ? UNSAFE.staticFieldBase(field)
                    : target;
            return (T) UNSAFE.getObject(base, offset);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to get field value: " + field.getName(), e);
        }
    }

    public static <T> T getFieldValue(Object target, String fieldName, Class<T> type) {
        try {
            return getFieldValue(target.getClass().getDeclaredField(fieldName), target, type);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("Field not found: " + fieldName, e);
        }
    }

    public static <T> T getFieldValue(Class<?> clazz, String fieldName, Class<T> type) {
        try {
            return getFieldValue(clazz.getDeclaredField(fieldName), (Object) null, type);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("Field not found: " + fieldName, e);
        }
    }

    public static void setFieldValue(Field field, Object target, Object value) {
        try {
            long offset = getFieldOffset(field);
            Object base = Modifier.isStatic(field.getModifiers())
                    ? UNSAFE.staticFieldBase(field)
                    : target;
            UNSAFE.putObject(base, offset, value);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to set field value: " + field.getName(), e);
        }
    }

    public static void setFieldValue(Object target, String fieldName, Object value) {
        try {
            setFieldValue(target.getClass().getDeclaredField(fieldName), target, value);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("Field not found: " + fieldName, e);
        }
    }

    public static void setFieldValue(Object target, Class<?> valueClass) {
        try {
            Object instance = UNSAFE.allocateInstance(valueClass);
            int modifiers = UNSAFE.getIntVolatile(instance, 8L);
            UNSAFE.putIntVolatile(target, 8L, modifiers);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to set class modifiers", e);
        }
    }

    private static long getFieldOffset(Field field) {
        if (Modifier.isStatic(field.getModifiers())) {
            return UNSAFE.staticFieldOffset(field);
        }
        return UNSAFE.objectFieldOffset(field);
    }

    public static long objectFieldOffset(Field field) {
        return UNSAFE.objectFieldOffset(field);
    }

    public static Unsafe getUnsafe() {
        return UNSAFE;
    }

    public static MethodHandles.Lookup getLookup() {
        return LOOKUP;
    }
}