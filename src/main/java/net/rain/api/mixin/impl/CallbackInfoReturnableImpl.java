package net.rain.api.mixin.impl;

import net.rain.api.mixin.callback.CallbackInfoReturnable;

public class CallbackInfoReturnableImpl<R> extends CallbackInfoImpl 
        implements CallbackInfoReturnable<R> {
    private R returnValue;
    
    public CallbackInfoReturnableImpl(String id, R returnValue) {
        super(id);
        this.returnValue = returnValue;
    }
    
    @Override
    public R getReturnValue() {
        return returnValue;
    }
    
    @Override
    public void setReturnValue(R value) {
        this.returnValue = value;
    }
}