package at.yawk.javabrowser.server;

import com.google.common.base.Stopwatch;
import com.google.common.collect.Iterators;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.tukaani.xz.XZInputStream;

/**
 * @author yawkat
 */
@Warmup(iterations = 5, time = 10)
@Measurement(iterations = 10, time = 10)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class SearchIndexPerformanceTest {
    private static final Object CATEGORY = new Object();

    @Benchmark
    public void benchmark(Blackhole blackhole) {
        Iterators.limit(Lazy.INDEX.find("urlencod", Collections.singleton(CATEGORY)).iterator(), 100)
                .forEachRemaining(blackhole::consume);
    }

    private static class Lazy {
        private static final SearchIndex<Object, Void> INDEX = new SearchIndex<>();

        static {
            System.out.println("Loading test data...");
            Stopwatch stopwatch = Stopwatch.createStarted();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new XZInputStream(SearchIndexPerformanceTest.class.getResourceAsStream("bindings.tsv.xz")),
                    StandardCharsets.UTF_8
            ))) {
                INDEX.replace(CATEGORY, r.lines().map(s -> new SearchIndex.Input<Void>(s, null)).iterator());
            } catch (IOException ioe) {
                throw new UncheckedIOException(ioe);
            }
            stopwatch.stop();
            System.out.println("Test data loaded in " + stopwatch.toString());
        }
    }

    public static void main(String[] args) throws RunnerException {
        Options options = new OptionsBuilder()
                .include(SearchIndexPerformanceTest.class.getSimpleName())
                .forks(1)
                .addProfiler(GCProfiler.class)
                .build();
        new Runner(options).run();
    }
}
