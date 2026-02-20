package net.rain.api.coremod.transformer;

import net.rain.api.coremod.manager.*;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import org.objectweb.asm.tree.ClassNode;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class RainClassTransformer implements ITransformer<ClassNode> {
    
    
    @Override
    public @NotNull ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        String className = context.getClassName(); // 从 context 获取类名
        
        try {
            return CoreModManager.transformClassNode(className, input);
        } catch (Exception e) {
            throw new RuntimeException("Transform failed: " + className, e);
        }
    }
    
    @Override
    public @NotNull TransformerVoteResult castVote(ITransformerVotingContext context) {
        String className = context.getClassName();
        
        // 排除检查
        if (className.startsWith("net.rain.api") || 
            className.startsWith("java.") ||
            className.startsWith("javax.")) {
            return TransformerVoteResult.REJECT;
        }
        
        if (!CoreModManager.hasTransformers()) {
            return TransformerVoteResult.REJECT;
        }
        
        return TransformerVoteResult.YES;
    }
    
    @Override
    public @NotNull Set<Target> targets() {
        return Set.of(); // 空表示由 castVote 决定
    }
}