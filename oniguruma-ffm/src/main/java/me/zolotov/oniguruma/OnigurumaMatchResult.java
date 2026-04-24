package me.zolotov.oniguruma;

import java.util.Arrays;
import java.util.Objects;

public final class OnigurumaMatchResult {
    private final int[] regionOffsets;

    OnigurumaMatchResult(int[] regionOffsets) {
        Objects.requireNonNull(regionOffsets, "regionOffsets");
        if (regionOffsets.length % 2 != 0) {
            throw new IllegalArgumentException("regionOffsets must contain start/end pairs");
        }
        this.regionOffsets = regionOffsets.clone();
    }

    public int[] regionOffsets() {
        return regionOffsets.clone();
    }

    public int captureCount() {
        return regionOffsets.length / 2;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof OnigurumaMatchResult other
            && Arrays.equals(this.regionOffsets, other.regionOffsets);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(regionOffsets);
    }

    @Override
    public String toString() {
        return "OnigurumaMatchResult" + Arrays.toString(regionOffsets);
    }
}
