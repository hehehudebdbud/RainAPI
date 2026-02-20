package net.rain.api.mixin.throwables;

public class MixinApplyError extends MixinError {

    private static final long serialVersionUID = 1L;

    public MixinApplyError(String message) {
        super(message);
    }

    public MixinApplyError(Throwable cause) {
        super(cause);
    }

    public MixinApplyError(String message, Throwable cause) {
        super(message, cause);
    }

}
