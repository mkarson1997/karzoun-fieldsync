# Roadmap

## Completed in v0.1.0

### Synchronization core
- bounded push/pull synchronization
- exact mutation IDs
- explicit retry/permanent outcomes
- cursor checkpoints
- idempotent remote replay
- deterministic conflict policy

### JVM durability reference
- SQLite JDBC store
- schema versioning
- WAL + FULL synchronous mode
- atomic local stage and remote-page commit
- process restart reconstruction
- duplicate-ID rollback/no-partial-state tests
- persistent retry/failure/replay/checkpoint state

### Release hardening
- Maven publication metadata
- reproducible JAR ordering/timestamps
- main JAR + sources JAR + POM
- checksums
- provenance attestation
- GitHub Release workflow

## Next

### Android-native integration
- Android SQLite/Room `SyncStore` adapter
- WorkManager-friendly runner
- connectivity-aware scheduling adapter
- lifecycle-safe cancellation boundaries

### Further hardening
- explicit corruption/open-failure behavior tests
- migration coverage for future schema versions
- larger deterministic fault-injection suites
