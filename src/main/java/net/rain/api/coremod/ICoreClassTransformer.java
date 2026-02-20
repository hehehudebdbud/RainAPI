package net.rain.api.coremod;

import org.objectweb.asm.tree.ClassNode;

public interface ICoreClassTransformer {
    ClassNode transform(String className, ClassNode basicClass);
}