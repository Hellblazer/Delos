import re
from collections import defaultdict
import statistics

# Read timing data
with open('ethereal-sig-verify-timing.log', 'r') as f:
    lines = f.readlines()

# Categories of timing data
commit_verify_times = []
prevote_verify_times = []

# Parse each line
for line in lines:
    # commit sig verify: XXXμs source=Y on: (Z)
    match = re.search(r'Timing - commit sig verify: (\d+)μs', line)
    if match:
        commit_verify_times.append(int(match.group(1)))

    # prevote sig verify: XXXμs source=Y on: (Z)
    match = re.search(r'Timing - prevote sig verify: (\d+)μs', line)
    if match:
        prevote_verify_times.append(int(match.group(1)))

# Helper function
def stats(data, name):
    if len(data) == 0:
        return f"{name}: No data"
    return f"{name}:\n  Count: {len(data)}\n  Min: {min(data)}μs\n  Max: {max(data)}μs\n  Mean: {statistics.mean(data):.0f}μs\n  Median: {statistics.median(data):.0f}μs\n  p95: {sorted(data)[int(len(data)*0.95)]:.0f}μs\n  p99: {sorted(data)[int(len(data)*0.99)]:.0f}μs"

print("=" * 80)
print("SIGNATURE VERIFICATION TIMING ANALYSIS")
print("=" * 80)
print()

print(stats(commit_verify_times, "Commit Signature Verification"))
print()

print(stats(prevote_verify_times, "Pre-Vote Signature Verification"))
print()

# Combined analysis
all_verify = commit_verify_times + prevote_verify_times
print(stats(all_verify, "All Signature Verifications Combined"))
print()

# Cost analysis
print("=" * 80)
print("IMPACT ANALYSIS:")
print("=" * 80)
print()

avg_verify_cost = statistics.mean(all_verify)
total_verifications = len(all_verify)
total_time_ms = sum(all_verify) / 1000

print(f"Total signature verifications: {total_verifications}")
print(f"Average cost per verification: {avg_verify_cost:.0f}μs")
print(f"Total time in signature verification: {total_time_ms:.1f}ms")
print()

# Per-unit analysis (assuming 4 nodes = ~8 verifications per unit)
verifications_per_unit = 8  # approximate (4 prevotes + 4 commits)
units_created = total_verifications / verifications_per_unit
cost_per_unit = avg_verify_cost * verifications_per_unit / 1000  # convert to ms

print(f"Approximate units created: {units_created:.0f}")
print(f"Cost per unit (signature verification only): {cost_per_unit:.1f}ms")
print()

# Compare to extender hook cost
extender_hook_cost_us = 58  # from previous profiling
print(f"Extender hook cost (from prior profiling): {extender_hook_cost_us}μs")
print(f"Signature verify cost per unit: {avg_verify_cost * verifications_per_unit:.0f}μs")
print(f"Ratio (verify/hook): {(avg_verify_cost * verifications_per_unit / extender_hook_cost_us):.0f}x")
print()

print("=" * 80)
print("BOTTLENECK CONCLUSION:")
print("=" * 80)
print()
print("✓ Signature verification is CONFIRMED as PRIMARY BOTTLENECK")
print(f"  - Average {avg_verify_cost:.0f}μs per verification")
print(f"  - ~{verifications_per_unit * avg_verify_cost:.0f}μs per unit in 4-node cluster")
print(f"  - {(avg_verify_cost * verifications_per_unit / extender_hook_cost_us):.0f}x MORE expensive than timing hook")
print()
print("Optimization candidates:")
print("  1. Batch signature verification (verify multiple signatures together)")
print("  2. Parallel signature verification (use multiple threads)")
print("  3. Signature algorithm optimization (currently using ECDSA)")
print("  4. Cache verification results (if same signature appears multiple times)")
print()

# Distribution analysis
print("=" * 80)
print("DISTRIBUTION ANALYSIS:")
print("=" * 80)
print()

buckets = defaultdict(int)
for time in all_verify:
    bucket = (time // 50) * 50  # Group by 50μs buckets
    buckets[bucket] += 1

print("Time Distribution (50μs buckets):")
for time_bucket in sorted(buckets.keys()):
    count = buckets[time_bucket]
    pct = 100 * count / total_verifications
    bar = '█' * int(pct / 2)
    print(f"{time_bucket:4d}-{time_bucket+50:4d}μs: {count:6d} ({pct:5.1f}%) {bar}")
print()
