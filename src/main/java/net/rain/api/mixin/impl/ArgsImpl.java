package net.rain.api.mixin.impl;

import net.rain.api.mixin.callback.Args;

public class ArgsImpl implements Args {
    private final Object[] args;
    
    public ArgsImpl(Object[] args) {
        this.args = args;
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(int index) {
        return (T) args[index];
    }
    
    @Override
    public <T> void set(int index, T value) {
        args[index] = value;
    }
    
    @Override
    public int size() {
        return args.length;
    }
    
    public Object[] getArgs() {
        return args;
    }
}