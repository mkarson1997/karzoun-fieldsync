# Roadmap

## v0.1 core
- bounded push/pull synchronization
- exact mutation IDs
- explicit retry/permanent outcomes
- cursor checkpoints
- idempotent remote replay
- deterministic conflict policy

## Durability
- SQLite-backed Android/JVM store
- schema versioning and migrations
- atomic local stage and remote-page commit
- process restart reconstruction
- corruption/error handling tests

## Android integration
- WorkManager-friendly runner
- connectivity-aware scheduling adapter
- lifecycle-safe cancellation boundaries

## Release hardening
- Maven package
- checksums
- provenance when useful
- release notes and compatibility matrix
