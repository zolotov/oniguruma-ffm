package me.zolotov.oniguruma;

import java.lang.foreign.MemorySegment;

/**
 * Native UTF-8 string buffer used by Oniguruma matching.
 *
 * <p>This is a thin native resource wrapper. It does not track whether it has been closed. Clients
 * must close each instance exactly once, must not use it after closing, and must close it before
 * closing the owning {@link Oniguruma} instance.
 */
public final class OnigurumaString implements AutoCloseable {
    private final Oniguruma owner;
    private final MemorySegment buffer;
    private final byte[] utf8Content;

    OnigurumaString(Oniguruma owner, MemorySegment buffer, byte[] utf8Content) {
        this.owner = owner;
        this.buffer = buffer;
        this.utf8Content = utf8Content;
    }

    public long handleId() {
        return buffer.address();
    }

    public byte[] utf8Content() {
        return utf8Content.clone();
    }

    @Override
    public void close() {
        owner.freeStringBuffer(buffer);
    }

    MemorySegment buffer() {
        return buffer;
    }

    Oniguruma owner() {
        return owner;
    }

    int byteLength() {
        return utf8Content.length;
    }
}
