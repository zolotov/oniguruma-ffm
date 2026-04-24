package me.zolotov.oniguruma;

import java.lang.foreign.MemorySegment;

/**
 * Native Oniguruma regular expression handle.
 *
 * <p>This is a thin native resource wrapper. It does not track whether it has been closed. Clients
 * must close each instance exactly once, must not use it after closing, and must close it before
 * closing the owning {@link Oniguruma} instance.
 */
public final class OnigurumaRegex implements AutoCloseable {
    private final Oniguruma owner;
    private final MemorySegment handle;
    private final byte[] pattern;

    OnigurumaRegex(Oniguruma owner, MemorySegment handle, byte[] pattern) {
        this.owner = owner;
        this.handle = handle;
        this.pattern = pattern;
    }

    public long handleId() {
        return handle.address();
    }

    public byte[] pattern() {
        return pattern.clone();
    }

    @Override
    public void close() {
        owner.freeRegexHandle(handle);
    }

    MemorySegment handle() {
        return handle;
    }

    Oniguruma owner() {
        return owner;
    }
}
