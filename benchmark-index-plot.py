#!/usr/bin/python3

import collections

Result = collections.namedtuple("Result", "benchmark chunk_size runtime runtime_error")

results = []

with open("benchmark-index-data-runtime.tsv") as f:
    for line in f:
        if line.startswith("#"):
            continue
        if line.strip() == "":
            continue
        (benchmark, chunk_size, runtime, runtime_error) = line.split()
        results.append(Result(benchmark, int(chunk_size), float(runtime), float(runtime_error)))

memory_by_jumps = {
    0: [],
    1: [],
    2: [],
    3: [],
    4: [],
    5: [],
}

with open("benchmark-index-data-memory.tsv") as f:
    for line in f:
        if line.startswith("#"):
            continue
        if line.strip() == "":
            continue
        (chunk_size, jumps, memory) = line.split()
        # assume ordered by chunk_size
        memory_by_jumps[int(jumps)].append(int(memory))

import matplotlib.pyplot as plt
import matplotlib.axes

x = [1, 2, 4, 8, 16, 32, 64, 128, 256, 512]

ax_runtime: matplotlib.axes.Axes
fig, ax_memory = plt.subplots()

ax_runtime: matplotlib.axes.Axes = ax_memory.twinx()

ax_memory.set_ylim(0, 7e9)
ax_memory.set_ylabel("memory/B")
bottom = [0 for _ in x]
for jumps in memory_by_jumps:
    here = memory_by_jumps[jumps]
    ax_memory.bar(x, here, bottom=bottom, width=map(lambda s: s / 2, x))
    bottom = [i + j for i, j in zip(bottom, here)]

plt.xlim(1, 512)
plt.xlabel("chunk size")
plt.xscale("log", basex=2)
ax_runtime.set_ylim(0.01, 100)
ax_runtime.set_ylabel("runtime/ms")
ax_runtime.set_yscale("log")
for benchmark in set([result.benchmark for result in results]):
    def find_y(chunk_size: int):
        for result in results:
            if result.chunk_size == chunk_size and result.benchmark == benchmark:
                return result.runtime
        assert False


    line, = ax_runtime.plot(x, list(map(find_y, x)))
    line.set_label(benchmark)

plt.show()
