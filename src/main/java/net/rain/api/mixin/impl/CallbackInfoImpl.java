package net.rain.api.mixin.impl;

import net.rain.api.mixin.callback.CallbackInfo;

public class CallbackInfoImpl implements CallbackInfo {
    private boolean cancelled = false;
    private final String id;
    
    public CallbackInfoImpl(String id) {
        this.id = id;
    }
    
    @Override
    public void cancel() {
        this.cancelled = true;
    }
    
    @Override
    public boolean isCancelled() {
        return cancelled;
    }
    
    @Override
    public String getId() {
        return id;
    }
}