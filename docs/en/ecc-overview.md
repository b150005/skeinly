# Everything Claude Code (ECC) Overview

## What is ECC?

Everything Claude Code (ECC) is an ecosystem of agents, skills, commands, hooks, and rules that extends Claude Code's capabilities for software development. It transforms Claude Code from a general-purpose AI assistant into a specialized development environment with structured workflows.

## Architecture

ECC's configuration lives in the `.claude/` directory (project-level) and `~/.claude/` (global). The key components are:

### Agents

Agents are specialized AI personas, each with a focused role. They are defined as `.md` files in `.claude/agents/` or `~/.claude/agents/`.

| Agent | Role |
|-------|------|
| **planner** | Creates implementation plans before coding begins |
| **tdd-guide** | Enforces test-driven development (write tests first) |
| **code-reviewer** | Reviews code for quality, security, and maintainability |
| **security-reviewer** | Scans for vulnerabilities (OWASP Top 10, secrets, injection) |
| **architect** | Designs system architecture and makes technical decisions |
| **build-error-resolver** | Diagnoses and fixes build failures |

Agents are invoked automatically by Claude Code when the task matches their specialization, or manually via the Agent tool.

### Skills

Skills are deep reference documents for specific implementation tasks. They live in `.claude/skills/` or `~/.claude/skills/` and are invoked with `/skill-name` syntax.

Examples:
- `/tdd` — Enforce test-driven development workflow
- `/code-review` — Run a comprehensive code review
- `/plan` — Create an implementation plan
- `/verify` — Run verification checks

### Rules

Rules define coding standards and conventions. They use a layered structure:

```
.claude/rules/
├── common/           # Language-agnostic principles
│   ├── coding-style.md
│   ├── testing.md
│   ├── security.md
│   └── ...
└── typescript/       # Language-specific overrides
    ├── coding-style.md
    ├── testing.md
    └── ...
```

Language-specific rules extend and can override common rules. For example, Go's pointer receivers override the general immutability preference.

### Hooks

Hooks are automated actions triggered by Claude Code events:

| Hook Type | When it Runs | Example |
|-----------|-------------|---------|
| **PreToolUse** | Before a tool executes | Validate parameters |
| **PostToolUse** | After a tool executes | Auto-format code after edits |
| **Stop** | When the session ends | Run final verification |

Hooks are configured in `.claude/settings.json` under the `hooks` key.

### Commands

Commands are reusable prompts invoked with `/command-name`. They live as `.md` files in `.claude/commands/`. Unlike skills (which are reference material), commands are action-oriented instructions.

## How It All Fits Together

```
User Request
    │
    ├─→ CLAUDE.md (project context)
    ├─→ Rules (coding standards)
    │
    ├─→ Planner Agent (creates plan)
    ├─→ TDD Guide Agent (write tests first)
    ├─→ Code Reviewer Agent (review changes)
    │
    ├─→ Hooks (auto-format, auto-lint)
    │
    └─→ CI/CD (GitHub Actions validates everything)
```

## Getting Started with ECC

1. **Install Claude Code**: Follow the [official installation guide](https://docs.anthropic.com/en/docs/claude-code/overview)
2. **Install ECC**: Clone the ECC repository and run the installer, or manually copy agents/skills/rules to `~/.claude/`
3. **Configure per project**: Create `.claude/CLAUDE.md` with project-specific context
4. **Use agents**: Claude Code automatically delegates to specialized agents based on the task

## References

Primary sources for the technologies described in this document:

- [Claude Code Overview](https://docs.anthropic.com/en/docs/claude-code/overview) — Official Claude Code documentation
- [Claude Code Configuration](https://docs.anthropic.com/en/docs/claude-code/settings) — Settings, CLAUDE.md, and project configuration
- [Claude Code Hooks](https://docs.anthropic.com/en/docs/claude-code/hooks) — PreToolUse, PostToolUse, and Stop hooks
- [Claude Code Agent Tool](https://docs.anthropic.com/en/docs/claude-code/sub-agents) — How agents (sub-agents) work
