package me.zolotov.oniguruma;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
public class OnigurumaCreateBenchmark {
    private Oniguruma oniguruma;
    private byte[] pattern;
    private byte[] text;

    @Setup(Level.Trial)
    public void setup() {
        oniguruma = Oniguruma.createFromResources();
        pattern = "[0-9]+".getBytes(StandardCharsets.UTF_8);
        text = "\uD83D\uDEA7\uD83D\uDEA7\uD83D\uDEA7 привет, мир 123!".getBytes(StandardCharsets.UTF_8);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        oniguruma.close();
    }

    @Benchmark
    public void benchmarkCreateRegex(Blackhole blackhole) {
        try (var regex = oniguruma.createRegex(pattern)) {
            blackhole.consume(regex);
        }
    }

    @Benchmark
    public void benchmarkCreateString(Blackhole blackhole) {
        try (var string = oniguruma.createString(text)) {
            blackhole.consume(string);
        }
    }
}
