import re
from collections import defaultdict
import statistics

# Read timing data from parallel verification run
with open('ethereal-parallel-verify-timing.log', 'r') as f:
    lines_parallel = f.readlines()

# Read timing data from sequential verification run (baseline)
with open('ethereal-sig-verify-timing.log', 'r') as f:
    lines_sequential = f.readlines()

def extract_verifications(lines):
    commit_verify_times = []
    prevote_verify_times = []

    for line in lines:
        match = re.search(r'Timing - commit sig verify: (\d+)μs', line)
        if match:
            commit_verify_times.append(int(match.group(1)))

        match = re.search(r'Timing - prevote sig verify: (\d+)μs', line)
        if match:
            prevote_verify_times.append(int(match.group(1)))

    return commit_verify_times, prevote_verify_times

# Extract data from both runs
commit_parallel, prevote_parallel = extract_verifications(lines_parallel)
commit_sequential, prevote_sequential = extract_verifications(lines_sequential)

# Combine all verifications
all_parallel = commit_parallel + prevote_parallel
all_sequential = commit_sequential + prevote_sequential

def stats(data, name):
    if len(data) == 0:
        return f"{name}: No data"
    return f"{name}:\n  Count: {len(data)}\n  Min: {min(data)}μs\n  Max: {max(data)}μs\n  Mean: {statistics.mean(data):.0f}μs\n  Median: {statistics.median(data):.0f}μs\n  p95: {sorted(data)[int(len(data)*0.95)]:.0f}μs\n  p99: {sorted(data)[int(len(data)*0.99)]:.0f}μs"

print("=" * 80)
print("PARALLEL SIGNATURE VERIFICATION SPEEDUP ANALYSIS")
print("=" * 80)
print()

print("SEQUENTIAL BASELINE:")
print(stats(all_sequential, "All Signature Verifications"))
print()
total_sequential = sum(all_sequential)
avg_sequential = statistics.mean(all_sequential)
count_sequential = len(all_sequential)
print(f"Total time: {total_sequential / 1000:.1f}ms")
print(f"Average cost per verification: {avg_sequential:.0f}μs")
print()

print("=" * 80)
print("PARALLEL VERIFICATION:")
print(stats(all_parallel, "All Signature Verifications"))
print()
total_parallel = sum(all_parallel)
avg_parallel = statistics.mean(all_parallel)
count_parallel = len(all_parallel)
print(f"Total time: {total_parallel / 1000:.1f}ms")
print(f"Average cost per verification: {avg_parallel:.0f}μs")
print()

print("=" * 80)
print("SPEEDUP ANALYSIS:")
print("=" * 80)
print()

speedup = avg_sequential / avg_parallel
total_speedup = (total_sequential / 1000) / (total_parallel / 1000)

print(f"Speedup per verification: {speedup:.2f}x")
print(f"Speedup on total time: {total_speedup:.2f}x")
print()

if speedup > 1:
    print(f"✓ Parallel verification achieved {speedup:.2f}x speedup per signature!")
    time_saved_ms = (total_sequential - total_parallel) / 1000
    print(f"✓ Total time saved: {time_saved_ms:.1f}ms ({total_speedup:.2f}x faster overall)")
    print()

    # Calculate expected per-unit savings
    verifications_per_unit = 8  # ~4 prevotes + 4 commits
    time_saved_per_unit = (avg_sequential - avg_parallel) * verifications_per_unit / 1000
    print(f"Per-unit improvements (4-node cluster):")
    print(f"  Before: {avg_sequential * verifications_per_unit:.0f}μs")
    print(f"  After:  {avg_parallel * verifications_per_unit:.0f}μs")
    print(f"  Saved:  {time_saved_per_unit:.2f}ms per unit")
else:
    print(f"⚠ No speedup achieved (parallel slower by {1/speedup:.2f}x)")

print()
print("CONCLUSION:")
print()

if speedup >= 2:
    print(f"✓ Parallel verification is SUCCESSFUL")
    print(f"  Achieved {speedup:.2f}x speedup (target: 2-4x)")
    print(f"  Each unit now takes {avg_parallel * verifications_per_unit:.0f}μs to verify instead of {avg_sequential * verifications_per_unit:.0f}μs")
    print(f"  Bottleneck reduced from {total_sequential / 1000:.1f}ms to {total_parallel / 1000:.1f}ms")
elif speedup >= 1.5:
    print(f"✓ Modest speedup achieved: {speedup:.2f}x")
    print(f"  Results acceptable but below target range (2-4x)")
elif speedup > 1:
    print(f"⚠ Minimal speedup: {speedup:.2f}x")
    print(f"  Parallel overhead may be limiting gains")
else:
    print(f"✗ Performance regression detected")
    print(f"  Parallel execution is {1/speedup:.2f}x slower than sequential")
    print(f"  Check ForkJoinPool saturation or GC pressure")

print()
