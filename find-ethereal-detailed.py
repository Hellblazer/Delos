import json

# Read all beads
beads = []
with open('.beads/issues.jsonl', 'r') as f:
    for line in f:
        if line.strip():
            try:
                beads.append(json.loads(line))
            except:
                pass

# Find all ethereal-related (any field)
ethereal_all = []
for b in beads:
    full_text = ' '.join([
        b.get('title', ''),
        b.get('description', ''),
        b.get('body', ''),
        b.get('notes', '')
    ]).lower()
    
    if 'ethereal' in full_text or 'chooseNextTimingUnits' in full_text or 'Delos-5hlw' in full_text:
        ethereal_all.append(b)

priority_order = {'P0': 0, 'P1': 1, 'P2': 2, 'P3': 3, 'P4': 4}
ethereal_all.sort(key=lambda x: (priority_order.get(x.get('priority', 'P4'), 99), x.get('title', '')))

print(f"ALL Ethereal-Related Beads (any status): {len(ethereal_all)} items\n")
print("=" * 120)

for b in ethereal_all:
    bead_id = b.get('id', 'UNKNOWN')
    priority = b.get('priority', 'P?')
    status = b.get('status', '?')
    bead_type = b.get('type', 'task')
    title = b.get('title', 'No title')
    print(f"{bead_id:15} [{priority}] [{status:10s}] [{bead_type:8s}] {title[:75]}")

print("=" * 120)

# Separate by status
open_ethereal = [b for b in ethereal_all if b.get('status') == 'open']
in_progress_ethereal = [b for b in ethereal_all if b.get('status') == 'in_progress']
closed_ethereal = [b for b in ethereal_all if b.get('status') == 'closed']

print(f"\nSUMMARY:")
print(f"  Open:        {len(open_ethereal)}")
print(f"  In Progress: {len(in_progress_ethereal)}")
print(f"  Closed:      {len(closed_ethereal)}")

if open_ethereal:
    print(f"\nOPEN ETHEREAL BEADS:")
    for b in open_ethereal:
        print(f"  {b.get('id')}: {b.get('title')}")

