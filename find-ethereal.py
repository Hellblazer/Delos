import json
import sys

# Read all beads
beads = []
with open('.beads/issues.jsonl', 'r') as f:
    for line in f:
        if line.strip():
            try:
                beads.append(json.loads(line))
            except:
                pass

# Filter for ethereal-related and open
ethereal_open = [b for b in beads if 'ethereal' in b.get('title', '').lower() and b.get('status') == 'open']

# Sort by priority
priority_order = {'P0': 0, 'P1': 1, 'P2': 2, 'P3': 3, 'P4': 4}
ethereal_open.sort(key=lambda x: (priority_order.get(x.get('priority', 'P4'), 99), x.get('title', '')))

print(f"Ethereal-related Open Beads: {len(ethereal_open)} items\n")
print("=" * 100)

for b in ethereal_open:
    bead_id = b.get('id', 'UNKNOWN')
    priority = b.get('priority', 'P?')
    bead_type = b.get('type', 'task')
    title = b.get('title', 'No title')
    print(f"{bead_id} [{priority}] [{bead_type:8s}] {title}")

print("=" * 100)

if not ethereal_open:
    print("\nNo open beads found specifically titled with 'ethereal'")
    print("\nSearching for beads in ethereal module or consensus-related...")
    # Try broader search
    consensus_related = [b for b in beads if any(word in b.get('title', '').lower() for word in ['consensus', 'aleph', 'bft', 'dag', 'unit', 'voting']) and b.get('status') == 'open']
    consensus_related.sort(key=lambda x: (priority_order.get(x.get('priority', 'P4'), 99), x.get('title', '')))
    
    print(f"\nConsensus/BFT-related Open Beads: {len(consensus_related)} items\n")
    for b in consensus_related[:20]:  # Limit to first 20
        bead_id = b.get('id', 'UNKNOWN')
        priority = b.get('priority', 'P?')
        bead_type = b.get('type', 'task')
        title = b.get('title', 'No title')
        print(f"{bead_id} [{priority}] [{bead_type:8s}] {title}")

