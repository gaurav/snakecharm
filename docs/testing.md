# Testing

How the SnakeCharm test suite is laid out, how to run one feature instead of all 3419, and the
traps that make a correct change look broken. Split out of `AGENTS.md`, which links here; see also
`DEVELOPER.md` for the setup steps (Configure Tests, Reading test results) and `PORTING.md` for
what each platform bump did to the suite.

## Running tests

Tests are **Cucumber/Gherkin** feature files under `src/test/resources/features/**`, executed
through a single JUnit runner, `AllCucumberFeaturesTest` (glue/step definitions in
`src/test/kotlin/features/glue/`). There is no per-feature test class.

- **Run one feature:** add a `@here` tag above its `Feature:` line (or above a single `Scenario:` /
  `Scenario Outline:`) and run with `CUCUMBER_TAGS='@here'`, which `test` forwards to cucumber's
  `cucumber.filter.tags`, composed with the runner's own `not @ignore`. Revert the tag afterwards.
  It turns a 25-minute suite into a ~60-second one. On a branch without that passthrough, set
  `tags = "not @ignore and @here"` in `AllCucumberFeaturesTest.kt` instead and revert that too.
- **Scenario isolation is thinner than it looks.** Every scenario asks IntelliJ's light-fixture
  framework for a test project by handing it a `LightProjectDescriptor` — the object that says
  which Python SDK and library roots the project needs. The framework hands back the *same* project
  as long as it is given the same descriptor, and rebuilds it when the descriptor changes. On `master`
  `StepDefs` constructs a fresh descriptor per scenario, so scenarios are mostly insulated from each
  other by accident. #577 has to cache descriptors instead — on 2026.2 an SDK is a workspace-model
  entity, so building a second mock SDK with the same name logs "symbolic id already exists", which
  `TestLoggerFactory` turns into ~1070 failed scenarios. Once the project is shared, everything held
  by a project-level *service* — framework enabled/disabled, settings, the configured SDK — survives
  into the next scenario. **Write steps that set the project state they need rather than assume a
  fresh project's defaults.** `Given a snakemake with disabled framework project` is the cautionary
  example: it never disabled anything, it only skipped the enabling, and it passed for years purely
  because each scenario used to start from a clean project.
- **`testData` is NOT a declared input of the `test` task.** After editing any feature or
  test-data file, run `./gradlew cleanTest test` — plain `test` may serve stale cached results.
- Test data lives in `testData/`. Snakemake API is mocked per-version under
  `testData/MockPackages3_smk_<version>/snakemake` (and a bare `testData/MockPackages3/snakemake`);
  cucumber steps select one via `Given a snakemake:<version> project`. Only the API files that
  differ between versions are copied into each mock (see `DEVELOPER.md` → Testdata).
- **Fresh-checkout gotcha (saves hours):** `testData/MockPackages3/snakemake` is **gitignored** and
  absent on a clean checkout — the *unversioned* `Given a snakemake project` scenarios (~135) then
  fail because `resolveQualifiedName("snakemake")` returns `[]`, while the checked-in per-version
  mocks (`MockPackages3_smk_<ver>`) still resolve. Provision it (see `DEVELOPER.md` → Configure Tests,
  step 2): point `testData/MockPackages3/snakemake` at the `src/snakemake` package of a
  [snakemake](https://github.com/snakemake/snakemake) checkout, at the release tag you want.
  **Two traps that make a correct fixture look like it does nothing:** the checkout must be at the
  version `snakemake_api.yaml` declares as `defaultVersion` (currently 9.9.0), and the test IDE
  sandbox persists a VFS/index under `.sandbox_pycharm/**/system-test/` that **`cleanTest` doesn't
  clear** — after adding the fixture to an already-tested checkout, remove it once with
  `find .sandbox_pycharm -maxdepth 3 -name system-test -exec rm -rf {} +` (its depth varies with
  how the tests were launched, so a fixed glob can silently match nothing). If you see a wall of
  `snakemake`-resolution failures on a fresh checkout, suspect this fixture, **not** your change.
  (Full write-up: PR #574.) Clearing it is **not free** — the next run re-indexes from scratch, and a
  full `cleanTest test` straight afterwards took **1h24m** on 2026.1. Clear it when the fixture
  actually changed, not as a routine "start clean".
- **A "missing" highlight may only be *demoted*.** `When I check highlighting <level>s` calls
  `CodeInsightTestFixture.checkHighlighting`, which reports only the requested severity (plus
  errors) and *silently discards the rest* — so a highlight whose severity dropped from `WARNING`
  to `WEAK WARNING` fails with the exact same `missing (…)` message as one that is not produced at
  all. Before hunting for a suppression, dump what is actually there: add a temporary step calling
  `fixture.doHighlighting()` and print each `HighlightInfo`'s `severity`, `type`, range,
  `description` and `inspectionToolId`. One run replaces a sandbox debugging session — that is how
  #584 was resolved. Platform bumps move these mappings: on 2026.1
  `ProblemHighlightType.LIKE_UNKNOWN_SYMBOL` renders as `HighlightInfoType.INFO` (weak warning),
  where 2025.2 gave a plain warning. **Fixing such a scenario by re-labelling its step costs
  coverage**, for the same reason: moving `warning`s to `weak warning`s stops it asserting anything
  at WARNING level, and in a scenario without `ignoring extra highlighting` that assertion was the
  guard against stray warnings. Use `I check highlighting warnings and weak warnings`, which asks
  for both.
- **A platform bump can move a check between inspections, and the scenario then passes vacuously.**
  `Given <X> inspection is enabled` fails loudly on an inspection that was *renamed*
  (`fail("Unknown inspection:…")`), but says nothing when the inspection still exists and merely
  stopped owning the diagnostic the scenario is about. The check for `expand(" ", **1)` moved from
  `PyArgumentListInspection` to `PyTypeCheckerInspection` in 2026.2, which is why one scenario lost
  its warning — and why its sibling, which asserts `expand(" ", **wildcards)` produces *no* warning,
  went on passing while guarding nothing at all. Trace the message to its owner rather than guessing:
  grep the message text in the platform's `messages/*.properties` for its bundle key, then grep the
  extracted plugin jars for the class that references that key, in both the old and new IDE. Two
  greps beat a type-inference theory — the key was renamed
  `INSP.expected.dict.got.type` → `INSP.type.checker.unpack.expected.mapping`, which names the new
  owner outright. A scenario asserting "no warning" is worth re-checking after any bump for exactly
  this reason.
- **Analyzing results:** the suite is large — ~3250 Cucumber scenarios plus ~170 plain JUnit tests.
  Budget around 25 minutes for a warm full `test` run — but that figure assumes a **warm Gradle
  daemon**: a `cleanTest test` started against a cold one measured ~2200 of 3419 tests at 59 minutes
  on 2026.1, i.e. ~95 minutes total, with the sandbox VFS untouched. Longer again if that VFS was
  cleared (see above: 1h24m measured). Either way, prefer the single-feature `@here` recipe while
  iterating. Gradle prints each failing scenario and a `N tests completed, M failed` summary, so tee
  the log and reduce it rather than parsing anything: `sed -n '/ > /s/ FAILED$//p' log | sort -u`
  gives a sorted list you can `diff` between two runs (the `/ > /` address skips Gradle's own
  `> Task :test FAILED`). Check the line count against `M failed`. See
  DEVELOPER.md → "Reading test results". The JUnit XML under `build/test-results/test/` holds the
  same information if you need a run whose console output you no longer have — but note it is
  written when the `test` task *ends*, and on an all-green run there is no `N tests completed`
  line either (Gradle prints that only on failure), so **a run in progress looks identical to a
  hung one** — and so does one that ran nothing. `BUILD SUCCESSFUL` says only that no test failed,
  never how many ran, and `CUCUMBER_TAGS` makes an empty run easy to reach: a tag expression
  matching no scenario exits 0 just as loudly as a full green suite. Read the count out of the XML
  (`<testsuite tests="…">`) before believing a green run; a full suite is 3419.
  The live signals are the test JVM's accumulating CPU time (`ps -o time=`) and the
  mtime of `build/test-results/test/binary/in-progress-results-generic.bin`; `jstat -gc` tells you
  whether a quiet stretch is a slow scenario or a GC death spiral. Budget generously on a
  memory-constrained machine: one all-green run measured **3h52m** on a swapping 16 GB laptop,
  against the ~95 minutes above.
