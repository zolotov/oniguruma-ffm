package me.zolotov.oniguruma;

public record OnigurumaMatchRequest(
    int byteOffset,
    boolean matchBeginPosition,
    boolean matchBeginString
) {
    public OnigurumaMatchRequest {
        if (byteOffset < 0) {
            throw new IllegalArgumentException("byteOffset must be non-negative");
        }
    }
}
