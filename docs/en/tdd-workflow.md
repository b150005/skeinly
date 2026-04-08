# TDD Workflow with ECC

## What is TDD?

Test-Driven Development (TDD) is a software development methodology where you write tests before writing the implementation code. This ensures that every piece of code has a corresponding test and that the implementation is driven by actual requirements.

## The RED-GREEN-IMPROVE Cycle

### 1. RED — Write a Failing Test

Write a test that describes the expected behavior. Run it. It should fail because the implementation does not exist yet.

```
# Pseudocode
test "calculate_total returns sum of item prices" {
  items = [{ price: 100 }, { price: 200 }]
  result = calculate_total(items)
  assert result == 300
}
```

**Why start with RED?** A test that passes immediately might not be testing what you think it is. Seeing it fail first confirms that the test is meaningful.

### 2. GREEN — Write Minimal Implementation

Write the simplest code that makes the test pass. Do not add extra functionality, optimization, or edge case handling yet.

```
# Pseudocode
function calculate_total(items) {
  sum = 0
  for item in items {
    sum = sum + item.price
  }
  return sum
}
```

### 3. IMPROVE — Refactor

Now that the test passes, improve the code structure without changing behavior. The test acts as a safety net — if it still passes after refactoring, you haven't broken anything.

```
# Pseudocode (refactored to functional style)
function calculate_total(items) {
  return items.reduce((sum, item) => sum + item.price, 0)
}
```

## ECC Agent: tdd-guide

The `tdd-guide` agent enforces the TDD workflow automatically:

1. When you request a new feature, it writes test scaffolds first
2. It runs the tests to confirm they fail (RED)
3. It implements the minimal code to pass (GREEN)
4. It suggests refactoring opportunities (IMPROVE)
5. It verifies coverage meets the 80% threshold

The agent is invoked automatically when Claude Code detects a feature request or bug fix, or manually via `/tdd`.

## Test Types

### Unit Tests

Test individual functions and modules in isolation.

- Mock external dependencies (databases, APIs, file system)
- Fast execution (milliseconds per test)
- High coverage target: aim for 80%+ line coverage

### Integration Tests

Test how components work together.

- Use real databases (test instances, not mocks) when possible
- Test API endpoint request/response cycles
- Verify database queries return expected results

### E2E Tests

Test complete user flows from start to finish.

- Use browser automation (Playwright recommended)
- Cover critical paths: login, core feature, checkout, etc.
- Slower but catches issues that unit/integration tests miss

## Coverage Requirements

This template enforces a minimum of **80% test coverage**. This is configured in the CI/CD pipeline and checked on every pull request.

Coverage is measured by:
- **Line coverage**: Percentage of code lines executed by tests
- **Branch coverage**: Percentage of conditional branches tested

## Best Practices

1. **One assertion per test** — Each test should verify one specific behavior
2. **Descriptive test names** — Test names should describe the expected behavior, not the implementation
3. **Test isolation** — Tests should not depend on each other or share mutable state
4. **Arrange-Act-Assert** — Structure each test with setup, execution, and verification phases
5. **Fix the implementation, not the test** — When a test fails, the implementation is usually wrong (unless the test has a genuine bug)

## Troubleshooting

When tests fail unexpectedly:

1. Check test isolation — is shared state leaking between tests?
2. Verify mocks — are they returning the expected values?
3. Check test environment — is the test database seeded correctly?
4. Use the `tdd-guide` agent for structured debugging assistance

## References

Primary sources for the concepts described in this document:

- [Test-Driven Development by Example (Kent Beck)](https://www.oreilly.com/library/view/test-driven-development/0321146530/) — The original TDD book
- [The Practical Test Pyramid (Martin Fowler)](https://martinfowler.com/articles/practical-test-pyramid.html) — Unit, integration, and E2E test layers
- [Given-When-Then (Cucumber)](https://cucumber.io/docs/gherkin/reference/) — Acceptance criteria format used in user stories
