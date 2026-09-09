# Changelog

All notable changes to Karzoun FieldSync are documented here.

## [0.1.0] - 2026-09-09

### Added

- bounded offline-first push/pull synchronization engine
- exact client mutation IDs and remote replay IDs
- explicit accepted, retryable, and permanent push outcomes
- cursor-based incremental pull with malformed-protocol guards
- deterministic `LocalPendingWins` and `ServerWins` conflict policies
- in-memory reference store
- file-backed JVM SQLite store with schema versioning
- WAL, `synchronous=FULL`, foreign keys, and busy timeout configuration
- persistent pending queue, retry attempts, permanent-failure bucket, replay IDs, and cursor checkpoint
- atomic local staging and remote-page commits
- close/reopen restart reconstruction tests
- JDK 21 and JDK 25 CI
- CodeQL Java/Kotlin analysis
- Dependabot configuration
- tag-driven release packaging with checksums and provenance

### Boundaries

- no bundled server implementation
- no exactly-once delivery claim
- no consensus or global ordering claim
- no CRDT convergence claim
- `SqliteSyncStore` is a JVM SQLite/JDBC reference implementation, not an Android-native storage adapter
