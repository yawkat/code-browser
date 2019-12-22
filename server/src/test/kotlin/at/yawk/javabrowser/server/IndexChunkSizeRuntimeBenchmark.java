package at.yawk.javabrowser.server;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.AverageTime)
public class IndexChunkSizeRuntimeBenchmark {
    static final int JUMP_MAX = 5;

    private IndexAutomaton<?>[] automata;

    @Param({""}) // set in runner config
    public String automataDir;

    @Param({"1", "2", "4", "8", "16", "32", "64", "128", "256", "512"})
    public int chunkSize;

    @Setup
    public void load() throws IOException, ClassNotFoundException {
        Path automataDir = Paths.get(this.automataDir);
        Path chunkSizeFile = automataDir.resolve(String.valueOf(chunkSize));
        if (Files.exists(chunkSizeFile)) {
            System.out.println("Loading automata...");
            try (ObjectInputStream ois = new ObjectInputStream(Files.newInputStream(chunkSizeFile))) {
                automata = (IndexAutomaton<?>[]) ois.readObject();
            }
        } else {
            automata = new IndexAutomaton[JUMP_MAX + 1];
            try (Stream<String> lines = Files.lines(automataDir.resolve("bindings.txt"))) {
                List<SearchIndex.SplitEntry> entries = lines.map(SearchIndex.SplitEntry::new).collect(Collectors.toList());
                for (int i = 0; i <= JUMP_MAX; i++) {
                    System.out.println("Building automaton of size " + i + " for chunk size " + chunkSize);
                    automata[i] = new IndexAutomaton<>(
                            entries,
                            splitEntry -> Arrays.asList(splitEntry.getComponentsLower()),
                            i,
                            chunkSize,
                            null
                    );
                }
            }
            System.out.println("Writing automata...");
            try (ObjectOutputStream oos = new ObjectOutputStream(Files.newOutputStream(chunkSizeFile))) {
                oos.writeObject(automata);
            }
        }
    }

    private void run(int jumps, Blackhole bh, String query) {
        Iterator<?> itr = automata[jumps].run(query);
        int i = 0;
        while (i++ < 100 && itr.hasNext()) {
            bh.consume(itr.next());
        }
        if (i == 0) {
            throw new AssertionError();
        }
    }

    @Benchmark
    public void string0(Blackhole bh) {
        run(0, bh, "string");
    }

    @Benchmark
    public void string1(Blackhole bh) {
        run(1, bh, "jstring");
    }

    @Benchmark
    public void string2(Blackhole bh) {
        run(2, bh, "jlstring");
    }

    @Benchmark
    public void hm0(Blackhole bh) {
        // java.util.concurrent.Concurrent[HashMap]
        run(0, bh, "hashmap");
    }

    @Benchmark
    public void chm1(Blackhole bh) {
        // java.util.concurrent.[C]oncurrent[HashMap]
        run(1, bh, "chashmap");
    }

    @Benchmark
    public void chm2(Blackhole bh) {
        // [j]ava.util.concurrent.[C]oncurrent[HashMap]
        run(2, bh, "jchashmap");
    }

    @Benchmark
    public void chm3(Blackhole bh) {
        // [j]ava.[u]til.concurrent.[C]oncurrent[HashMap]
        run(3, bh, "juchashmap");
    }

    @Benchmark
    public void chm4(Blackhole bh) {
        // [j]ava.[u]til.concurrent.[C]oncurrent[Ha]sh[Ma]p
        run(4, bh, "juchama");
    }

    @Benchmark
    public void chm5(Blackhole bh) {
        // [j]ava.[u]til.[c]oncurrent.[C]oncurrent[Ha]sh[Ma]p
        run(5, bh, "jucchama");
    }

    @Benchmark
    public void juc0(Blackhole bh) {
        run(0, bh, "java.util.conc");
    }

    @Benchmark
    public void juc1(Blackhole bh) {
        run(1, bh, "javconc");
    }

    @Benchmark
    public void juc2(Blackhole bh) {
        run(2, bh, "javuticonc");
    }
}
