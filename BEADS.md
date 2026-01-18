# Beads Task Management

This project uses **beads** for distributed task and issue tracking. All work is tracked as "beads" (issues) rather than ad-hoc TODOs or markdown notes.

## Quick Start

### Finding Work
```bash
bd ready                          # Show unblocked work
bd list --status=open             # List all open issues
bd show <id>                       # View issue details with dependencies
```

### Creating Work
```bash
bd create "Title" -t bug -p 1     # Create a new issue (bug, feature, task, epic, chore)
bd update <id> --status in_progress
```

### Completing Work
```bash
bd close <id>                     # Mark issue complete
bd close <id1> <id2> ...          # Close multiple (more efficient)
```

### Managing Dependencies
```bash
bd dep add <issue> <blocks-id>    # Mark dependency (issue depends on blocks-id)
bd blocked                        # Show all blocked issues
```

### Syncing Work
```bash
bd sync                           # Push to remote sync branch
```

## Infrastructure

### Metadata Storage (Orphan Branch)
Beads metadata is tracked on a dedicated **orphan branch** `beads-sync`:

- **Location**: All task metadata lives in `.beads/issues.jsonl`
- **Storage**: Committed to `beads-sync` orphan branch (not main branch)
- **Why**: Keeps task tracking separate from code history
- **Config**: Configured in `.beads/config.yaml` (checked into main branch)

### Configuration
See `.beads/config.yaml` for:
- `sync-branch: "beads-sync"` - Where metadata is pushed
- `no-db: true` - Uses JSONL as source of truth (no SQLite required)
- Other sync and storage options

### Workflow

1. **Create/Update Issues**: `bd create`, `bd update`, etc.
2. **Local JSONL Updates**: Changes written to `.beads/issues.jsonl`
3. **Sync to Remote**: `bd sync` commits to `beads-sync` branch
4. **Persist Across Sessions**: Pull `beads-sync` to restore all metadata

## Issue Prefixes

Issues in this repository use the prefix `Delos-` (e.g., `Delos-123`).

Common types:
- `bug` - Defects and failures
- `feature` - New functionality
- `task` - Work items
- `epic` - Large initiatives with sub-tasks
- `chore` - Maintenance and infrastructure

## Session Management

### Starting a Session
```bash
bd ready                    # See what work is available
bd show <id>               # Review issue details
bd update <id> --status in_progress
```

### Ending a Session
```bash
bd close <completed-ids>
bd sync                    # Push changes to beads-sync
git push                   # Push code changes to main
```

## Integration with Project Management

- **Beads** tracks detailed task state, dependencies, and metadata
- **GitHub Issues** integration available (experimental)
- **Slack notifications** can be configured (future)

## Documentation
- `.beads/README.md` - Technical beads documentation
- `.beads/config.yaml` - Configuration reference
- `.pm/` - Project management infrastructure (when present)

## Tips

- Always close beads when work completes - don't leave them in `in_progress`
- Use meaningful titles - they appear in reports and status views
- Add context to bead descriptions for future maintainers
- Group related work with the same epic
- Use dependency tracking to prevent blocked work

For more information, see the beads documentation or run `bd help`.
