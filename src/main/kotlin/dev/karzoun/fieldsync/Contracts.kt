package dev.karzoun.fieldsync

public interface SyncTransport {
    /** Returns exactly one outcome for every submitted mutation ID. */
    public suspend fun push(mutations: List<PendingMutation>): List<PushOutcome>

    /** Returns the next incremental page after [cursor]. */
    public suspend fun pull(cursor: String?, limit: Int): PullPage
}

public fun interface ConflictPolicy {
    public fun resolve(localPending: PendingMutation, remote: RemoteMutation): ConflictDecision

    public companion object {
        public val LocalPendingWins: ConflictPolicy =
            ConflictPolicy { _, _ -> ConflictDecision.KEEP_LOCAL }

        public val ServerWins: ConflictPolicy =
            ConflictPolicy { _, _ -> ConflictDecision.APPLY_REMOTE_AND_DROP_LOCAL }
    }
}

/**
 * Persistence boundary for FieldSync.
 *
 * Durable implementations must make stageLocal atomic with the local replica write,
 * and commitRemotePage atomic across remote application, deduplication markers,
 * conflict cleanup, and checkpoint advancement.
 */
public interface SyncStore {
    public suspend fun stageLocal(mutation: PendingMutation)

    public suspend fun pending(limit: Int): List<PendingMutation>

    public suspend fun pendingFor(entity: EntityKey): PendingMutation?

    public suspend fun markAccepted(mutationId: MutationId, serverVersion: Long)

    public suspend fun markRetryable(mutationId: MutationId)

    public suspend fun markPermanentFailure(mutationId: MutationId, reason: String)

    public suspend fun checkpoint(): String?

    public suspend fun wasRemoteApplied(remoteMutationId: String): Boolean

    public suspend fun commitRemotePage(
        plans: List<RemoteApplyPlan>,
        nextCursor: String?,
    )

    public suspend fun record(entity: EntityKey): ReplicaRecord?

    public suspend fun pendingCount(): Int

    public suspend fun permanentFailureCount(): Int
}
