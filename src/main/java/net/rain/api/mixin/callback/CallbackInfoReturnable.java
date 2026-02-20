package net.rain.api.mixin.callback;

public interface CallbackInfoReturnable<R> extends CallbackInfo {
    R getReturnValue();
    void setReturnValue(R value);
}