package me.zolotov.oniguruma;

public class OnigurumaException extends RuntimeException {
    public OnigurumaException(String message) {
        super(message);
    }

    public OnigurumaException(String message, Throwable cause) {
        super(message, cause);
    }
}
