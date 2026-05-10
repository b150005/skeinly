# Typical Research-Error Patterns

Five patterns the Critic checklist (`checklist.md`) is designed to catch.
Loaded on demand from `SKILL.md`. Each pattern includes a concrete failure
example, why a single research pass misses it, and which Critic checklist
items detect it.

## 1. Version mismatch

**Pattern.** A blog post or Q&A answer describes the framework's behaviour
under version N-1. The model surfaces this as the current answer. The
project is on version N, where the API has changed.

**Example.** "Next.js Server Actions require `'use server'` at the top of
the file" — true in 13.x with the experimental API, but the stable API in
14.x supports per-function `'use server'`. A blog from 2023 will state
the file-level rule as universal.

**Why one pass misses it.** Freshness-safe queries return the most recent
documents; secondary documents lag primary docs by months. The Generator
sees a confident-looking blog and accepts it.

**Caught by.** Checklist items 2 (version pinning), 8 (breaking-change
awareness), 1 (primary source present).

## 2. Secondary-source decay

**Pattern.** A claim is paraphrased through a chain of blogs. By the time
it reaches the Generator's search results, an important nuance is missing
(a parameter is omitted, a precondition is dropped, a return type is
generalized).

**Example.** "Use `await prisma.$transaction([...])` to run queries in a
transaction." Strictly true, but the primary docs note that `$transaction`
in array form does **not** roll back on application-level error — only on
DB-level error. A blog explaining transactions often omits this.

**Why one pass misses it.** The blog reads correct in isolation. The omission
is invisible without going to the primary source.

**Caught by.** Checklist items 1 (primary source present), 5 (conditional
claims explicit).

## 3. Similar-name confusion

**Pattern.** Two libraries (or two functions in the same library, or a
function and a method) share a name. The Generator's search returns the
wrong one and the answer is internally consistent but about a different
thing.

**Example.** "Use `pickle` to serialize NumPy arrays" — works for some
arrays, but `numpy.save` / `numpy.load` is the recommended primitive,
and `pickle` has known security issues. The model conflates the two
because both can serialize arrays.

**Why one pass misses it.** The wrong-but-coherent answer is plausible.
The Generator does not have a reason to look for the right one.

**Caught by.** Checklist items 6 (argument names match types), 9 (no
internal contradiction — the recommended primitive in primary docs will
contradict the chosen one).

## 4. Hallucinated API

**Pattern.** The model invents an argument, a default value, or a method
that does not exist. Often happens with new versions or rarely-used APIs
where the model's training data is thin.

**Example.** "`fs.readFile` accepts a `signal` option for AbortController"
— true since Node.js 15.x, so this one is real. Compare: "`fs.readFile`
accepts a `timeout` option" — sounds plausible, does not exist. A model
unsure of the API will sometimes confabulate.

**Why one pass misses it.** Hallucinated APIs read fluently. The Generator
has no automatic ground-truth check inside its own session.

**Caught by.** Checklist items 6 (argument names match types/source), 7
(default values match source), 10 (hallucination check — URL resolves,
method appears in primary source).

## 5. Context outscoping

**Pattern.** A claim true in one runtime/configuration is presented as
universal. The constraint that limits its applicability is not stated.

**Example.** "`structuredClone` works for all JSON-serializable values" —
true in Node.js 17+ and modern browsers, but fails on older runtimes the
project may still target. A claim "use `structuredClone` for deep copy"
without the runtime constraint is wrong for half the projects that read it.

**Why one pass misses it.** The claim is true *in some context*. Without
the runtime/version pin, the Generator does not know to add the constraint.

**Caught by.** Checklist items 5 (conditional claims explicit), 2 (version
pinning), 8 (breaking-change awareness — backwards from new APIs).

## Putting it together

These five patterns map onto the four severities in `SKILL.md`:

| Pattern | Typical severity |
|---|---|
| Hallucinated API | CRITICAL or HIGH |
| Version mismatch | HIGH |
| Context outscoping | HIGH |
| Similar-name confusion | HIGH or MEDIUM |
| Secondary-source decay | MEDIUM |

The Critic does not need to classify the *pattern* — only the *severity*.
The patterns above are background, not output. They explain why the
checklist exists in the form it does.

## See also

- [`SKILL.md`](./SKILL.md) — protocol overview
- [`checklist.md`](./checklist.md) — the 10-item checklist these patterns
  motivate
