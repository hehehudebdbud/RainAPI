package net.rain.api.coremod.error;

public class TransformApplyError extends Error {

    private static final long serialVersionUID = 1L;

    public TransformApplyError() {
    }

    public TransformApplyError(String message) {
        super(message);
    }

    public TransformApplyError(Throwable cause) {
        super(cause);
    }

    public TransformApplyError(String message, Throwable cause) {
        super(message, cause);
    }

}
