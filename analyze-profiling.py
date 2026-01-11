import re
from collections import defaultdict
import statistics

# Read timing data
with open('timing-data.txt', 'r') as f:
    lines = f.readlines()

# Categories of timing data
choose_times = []
nextround_voting_times = defaultdict(list)
nextround_maxLevel = []
nextround_unitsOnLevel = []
nextround_permutation = []
nextround_total = []
orderedUnits_times = []

# Parse each line
for line in lines:
    # chooseNextTimingUnits total: XXXμs (N rounds)
    match = re.search(r'chooseNextTimingUnits total: (\d+)μs', line)
    if match:
        choose_times.append(int(match.group(1)))
    
    # nextRound detailed breakdown
    match = re.search(r'nextRound (DECIDED|UNDECIDED|NO_DECISION).*?maxLevel: (\d+)μs.*?unitsOnLevel: (\d+)μs.*?permutation: (\d+)μs.*?voting\((\d+) units\): (\d+)μs.*?total: (\d+)μs', line)
    if match:
        status, maxLvl, units, perm, unit_count, voting, total = match.groups()
        maxLvl, units, perm, unit_count, voting, total = int(maxLvl), int(units), int(perm), int(unit_count), int(voting), int(total)
        nextround_maxLevel.append(maxLvl)
        nextround_unitsOnLevel.append(units)
        nextround_permutation.append(perm)
        nextround_total.append(total)
        nextround_voting_times[status].append(voting)
    
    # orderedUnits
    match = re.search(r'orderedUnits: (\d+)μs', line)
    if match:
        orderedUnits_times.append(int(match.group(1)))

# Helper function
def stats(data, name):
    if len(data) == 0:
        return f"{name}: No data"
    return f"{name}:\n  Count: {len(data)}\n  Min: {min(data)}μs\n  Max: {max(data)}μs\n  Mean: {statistics.mean(data):.0f}μs\n  Median: {statistics.median(data):.0f}μs\n  p95: {sorted(data)[int(len(data)*0.95)]:.0f}μs\n  p99: {sorted(data)[int(len(data)*0.99)]:.0f}μs"

print("=" * 80)
print("PROFILING ANALYSIS: Delos-5hlw chooseNextTimingUnits() Bottleneck")
print("=" * 80)
print()

print(stats(choose_times, "chooseNextTimingUnits() Overall"))
print()

print(stats(nextround_total, "nextRound() Total Time"))
print()

print("nextRound() Component Breakdown (% of total time):")
print(f"  unitsOnLevel (reads): {statistics.mean(nextround_unitsOnLevel):.0f}μs avg ({100*statistics.mean(nextround_unitsOnLevel)/statistics.mean(nextround_total):.1f}% of total)")
print(f"  permutation (sorting): {statistics.mean(nextround_permutation):.0f}μs avg ({100*statistics.mean(nextround_permutation)/statistics.mean(nextround_total):.1f}% of total)")
print(f"  voting (decision):     {statistics.mean([v for vals in nextround_voting_times.values() for v in vals]):.0f}μs avg ({100*statistics.mean([v for vals in nextround_voting_times.values() for v in vals])/statistics.mean(nextround_total):.1f}% of total)")
print(f"  maxLevel (reads):      {statistics.mean(nextround_maxLevel):.0f}μs avg ({100*statistics.mean(nextround_maxLevel)/statistics.mean(nextround_total):.1f}% of total)")
print()

for status in ['DECIDED', 'UNDECIDED', 'NO_DECISION']:
    if status in nextround_voting_times and len(nextround_voting_times[status]) > 0:
        print(stats(nextround_voting_times[status], f"Voting Time ({status})"))
        print()

print(stats(orderedUnits_times, "orderedUnits() Execution Time"))
print()

# Find slowest operations
print("=" * 80)
print("BOTTLENECK IDENTIFICATION:")
print("=" * 80)

# Calculate percentages
voting_avg = statistics.mean([v for vals in nextround_voting_times.values() for v in vals])
units_avg = statistics.mean(nextround_unitsOnLevel)
perm_avg = statistics.mean(nextround_permutation)
maxlvl_avg = statistics.mean(nextround_maxLevel)
total_avg = statistics.mean(nextround_total)

components = {
    'Voting (decision algorithm)': voting_avg,
    'Reads (unitsOnLevel)': units_avg,
    'Permutation (sorting)': perm_avg,
    'MaxLevel (reads)': maxlvl_avg,
}

print("\nTime Distribution in nextRound():")
for name, value in sorted(components.items(), key=lambda x: x[1], reverse=True):
    pct = 100 * value / total_avg
    bar = '█' * int(pct / 5)
    print(f"{name:40s}: {value:7.0f}μs ({pct:5.1f}%) {bar}")

print()
print("CONCLUSION:")
if voting_avg > total_avg * 0.5:
    print(f"✓ PRIMARY BOTTLENECK: Voting Algorithm ({100*voting_avg/total_avg:.1f}% of nextRound time)")
    print("  Recommendation: Optimize UnanimousVoter voting logic")
elif units_avg > total_avg * 0.3:
    print(f"✓ PRIMARY BOTTLENECK: DAG Reads ({100*units_avg/total_avg:.1f}% of nextRound time)")
    print("  Recommendation: Optimize dag.unitsOnLevel() or use lock-free reads")
else:
    print("✓ BALANCED: Multiple components contribute equally")
    print("  Recommendation: Consider Option B (split hooks) or Option C (batching)")

print("\nNote: orderedUnits() is called after hook returns, not on critical path")

