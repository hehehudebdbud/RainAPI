package net.rain.api.mixin.callback;

public interface CallbackInfo {
    void cancel();
    boolean isCancelled();
    String getId();
}