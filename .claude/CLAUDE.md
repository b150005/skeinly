# Project Context

## About This Project

> **Replace this section** with your project's specific context after creating a repository from this template.

This project was created from the [ECC Base Template](https://github.com/b150005/ecc-base-template).

## Architecture Principles

- Layered architecture with clear separation of concerns
- Repository pattern for data access abstraction
- Immutable data structures preferred; copy-on-write for state updates
- Dependency injection for testability

## Agent Team

This project uses an agent team for structured development. The **orchestrator** agent coordinates the following specialists:

| Agent | Role |
|-------|------|
| orchestrator | Analyzes issues, creates plans, delegates to specialists |
| product-manager | Product planning, PRD, user stories, acceptance criteria |
| market-analyst | Market research, competitor analysis |
| monetization-strategist | Business model, pricing, revenue strategy |
| ui-ux-designer | UI/UX design, accessibility, usability review |
| architect | System architecture, technology decisions |
| implementer | Code implementation following architecture and TDD |
| code-reviewer | Code quality and standards review |
| test-runner | Test execution, coverage reporting |
| linter | Static analysis, code style enforcement |
| security-reviewer | Vulnerability detection, OWASP Top 10 |
| performance-engineer | Profiling, bottleneck identification, optimization |
| devops-engineer | CI/CD, deployment strategy, release management |
| technical-writer | Documentation, changelog, bilingual docs |

All agents detect the project ecosystem at runtime by reading this file and project manifest files (package.json, pubspec.yaml, go.mod, etc.).

## Development Workflow

1. **Issue Analysis**: Feed issues to the orchestrator via GitHub MCP or copy-paste
2. **Product Planning**: The product-manager creates PRD, user stories, and acceptance criteria
3. **Research & Reuse**: Search GitHub, package registries, and docs before writing new code
4. **Architecture**: The architect designs the solution; significant decisions are recorded as ADRs
5. **Implementation**: The implementer writes code following TDD (RED → GREEN → IMPROVE)
6. **Quality Gate**: The code-reviewer, linter, security-reviewer, and performance-engineer validate the implementation
7. **Documentation**: The technical-writer updates docs and changelog
8. **Release**: The devops-engineer manages deployment and release
9. **Commit**: Conventional commits format (feat, fix, refactor, docs, test, chore, perf, ci)

## Testing Requirements

- Minimum 80% test coverage
- Unit tests for individual functions
- Integration tests for API/database operations
- E2E tests for critical user flows

## Code Quality Standards

- Functions: < 50 lines
- Files: 200-400 lines typical, 800 max
- Validate all inputs at system boundaries
- Handle errors explicitly at every level
- Never hardcode secrets; use environment variables

## Documentation Convention

- Technology reference docs: `docs/en/` (English, source of truth)
- Japanese translations: `docs/ja/` (maintained translations)
- Claude reads `docs/en/` only to minimize context window usage
- Japanese files include a header linking to the English source

## Extending This File

Derived projects should:

1. Replace the "About This Project" section with project-specific context
2. Add framework-specific architecture details (e.g., state management, routing)
3. Add framework-specific testing tools (e.g., Jest, pytest, go test)
4. Add framework-specific code style rules (e.g., Biome, Ruff, gofmt)
5. Keep the universal sections (workflow, testing requirements, code quality)
