package me.zolotov.oniguruma;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
public class OnigurumaMatchBenchmark {
    private Oniguruma oniguruma;
    private OnigurumaString string;
    private OnigurumaRegex regex;

    @Setup(Level.Trial)
    public void setup() {
        oniguruma = Oniguruma.createFromResources();
        regex = oniguruma.createRegex("[0-9]+".getBytes(StandardCharsets.UTF_8));
        string = oniguruma.createString(
            "\uD83D\uDEA7\uD83D\uDEA7\uD83D\uDEA7 привет, мир 123!".getBytes(StandardCharsets.UTF_8)
        );
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        oniguruma.freeRegex(regex);
        oniguruma.freeString(string);
        oniguruma.close();
    }

    @Benchmark
    public OnigurumaMatchResult benchmarkMatch() {
        return oniguruma.match(
            regex,
            string,
            0,
            true,
            true
        );
    }
}
