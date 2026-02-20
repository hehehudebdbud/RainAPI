package net.rain.api.mixin.callback;

public interface Args {
    <T> T get(int index);
    <T> void set(int index, T value);
    int size();
}