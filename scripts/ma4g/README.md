# MA4G isolated verification fixtures

These fixtures exercise the deterministic fake-provider boundary described in
`docs/ResearchAgent-MA4G-Atomic-Execution-Finalization-Execution-Plan.md`.
They do not prove real provider quality, long-running lease safety, automatic
Run advancement, or production readiness.

Safety rules are enforced by the PowerShell runner:

- every Compose project must match `noteweave-ma4g-*`;
- no service publishes a host port;
- the network is project-scoped and `internal: true`;
- every round uses a new project and new named volumes;
- cleanup only targets the validated project passed to the runner.

Static validation (does not start containers):

```powershell
./scripts/ma4g/static-check.ps1
```

The static check validates the LockMatrix wiring but does not execute its
Maven test or start MySQL.

Round execution is intentionally explicit. Use a unique project for each run:

```powershell
./scripts/ma4g/run-round.ps1 -Round Migration -ProjectName noteweave-ma4g-migration-local
./scripts/ma4g/run-round.ps1 -Round LockMatrix -ProjectName noteweave-ma4g-lockmatrix-local
./scripts/ma4g/run-round.ps1 -Round G1 -ProjectName noteweave-ma4g-g1-local
./scripts/ma4g/run-round.ps1 -Round G2 -ProjectName noteweave-ma4g-g2-local
./scripts/ma4g/run-round.ps1 -Round G3 -ProjectName noteweave-ma4g-g3-local
./scripts/ma4g/run-round.ps1 -Round G4 -ProjectName noteweave-ma4g-g4-local
```

`run-round.ps1` records evidence under `scripts/ma4g/evidence/` and cleans the
isolated project by default. Passing `-KeepProject` retains it for diagnosis.
An evidence directory is not a pass by itself: the round is verified only when
the runner exits zero and its manifest records `VERIFIED`.

G2 is a real MySQL transaction interruption. A test fixture trigger sleeps on
the second cell CAS; a separate root connection identifies the Backend session
and issues `KILL CONNECTION`. The verifier compares a digest captured after
claim with the digest after the connection loss, then sets the failed lease to
`current_timestamp - interval 1 second` inside MySQL before calling the bodyless
DB-clock expiry endpoint and proving recovery under a new epoch. G3 is also
outside H2: an internal proxy waits until the Backend has
returned the first successful `/complete` response, drops that response, checks
that the retry body and idempotency header are byte-identical, and only then
allows exact replay.

`LockMatrix` is separate from G1–G4 and has no provider or Kafka claim. It runs
`ResearchAgentMySqlLockMatrixIT` against MySQL 8.4 at READ COMMITTED. Test-only
triggers wait on a fixture mutex only after the leading business transaction
has acquired the upstream Run/task locks. `performance_schema.data_lock_waits`
must then prove a follower → leader → gate-holder chain on three distinct
physical connection ids. Cases A–L cover complete/cancel, expire/reaper,
claim/heartbeat/cancel, coordinator/cancel, identical replay, and conflicting
completion order. The triggers and fixture tables are removed before the round
can report `VERIFIED`.
