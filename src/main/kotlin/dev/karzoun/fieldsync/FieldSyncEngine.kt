package dev.karzoun.fieldsync

/**
 * One bounded synchronization cycle.
 *
 * The engine deliberately owns no scheduler and no retry sleep. Android hosts can
 * run it from WorkManager or another lifecycle-aware scheduler.
 */
public class FieldSyncEngine(
    private val store: SyncStore,
    private val transport: SyncTransport,
    private val conflictPolicy: ConflictPolicy = ConflictPolicy.LocalPendingWins,
    private val pushBatchSize: Int = 100,
    private val pullBatchSize: Int = 100,
    private val maxPullPages: Int = 20,
) {
    init {
        require(pushBatchSize > 0) { "pushBatchSize must be positive" }
        require(pullBatchSize > 0) { "pullBatchSize must be positive" }
        require(maxPullPages > 0) { "maxPullPages must be positive" }
    }

    public suspend fun syncOnce(): SyncReport {
        var pushed = 0
        var retryableFailures = 0
        var permanentFailures = 0
        var remoteSeen = 0
        var remoteApplied = 0
        var conflictsKeptLocal = 0
        var pulledPages = 0

        val localBatch = store.pending(pushBatchSize)

        if (localBatch.isNotEmpty()) {
            val outcomes = transport.push(localBatch)
            validatePushProtocol(localBatch, outcomes)

            for (outcome in outcomes) {
                when (outcome) {
                    is PushOutcome.Accepted -> {
                        store.markAccepted(outcome.mutationId, outcome.serverVersion)
                        pushed += 1
                    }

                    is PushOutcome.RetryableFailure -> {
                        store.markRetryable(outcome.mutationId)
                        retryableFailures += 1
                    }

                    is PushOutcome.PermanentFailure -> {
                        store.markPermanentFailure(outcome.mutationId, outcome.reason)
                        permanentFailures += 1
                    }
                }
            }
        }

        var cursor = store.checkpoint()

        repeat(maxPullPages) {
            val page = transport.pull(cursor, pullBatchSize)
            pulledPages += 1
            validatePullProtocol(cursor, page)

            val plans = ArrayList<RemoteApplyPlan>(page.mutations.size)

            for (remote in page.mutations) {
                remoteSeen += 1

                if (store.wasRemoteApplied(remote.remoteMutationId)) {
                    plans += RemoteApplyPlan(
                        mutation = remote,
                        decision = ConflictDecision.KEEP_LOCAL,
                    )
                    continue
                }

                val localPending = store.pendingFor(remote.entity)
                val decision = if (localPending == null) {
                    ConflictDecision.APPLY_REMOTE_AND_DROP_LOCAL
                } else {
                    conflictPolicy.resolve(localPending, remote)
                }

                if (decision == ConflictDecision.KEEP_LOCAL && localPending != null) {
                    conflictsKeptLocal += 1
                } else {
                    remoteApplied += 1
                }

                plans += RemoteApplyPlan(
                    mutation = remote,
                    decision = decision,
                )
            }

            store.commitRemotePage(plans, page.nextCursor)
            cursor = page.nextCursor

            if (!page.hasMore) {
                return SyncReport(
                    pushed = pushed,
                    retryableFailures = retryableFailures,
                    permanentFailures = permanentFailures,
                    remoteSeen = remoteSeen,
                    remoteApplied = remoteApplied,
                    conflictsKeptLocal = conflictsKeptLocal,
                    pulledPages = pulledPages,
                )
            }
        }

        error("Remote transport exceeded maxPullPages=$maxPullPages without completing the pull cycle")
    }

    private fun validatePushProtocol(
        requested: List<PendingMutation>,
        outcomes: List<PushOutcome>,
    ) {
        val requestedIds = requested.map { it.mutationId }.toSet()
        val outcomeIds = outcomes.map { it.mutationId }

        require(outcomeIds.size == outcomeIds.toSet().size) {
            "Transport returned duplicate push outcomes"
        }

        require(outcomeIds.toSet() == requestedIds) {
            "Transport must return exactly one outcome for each submitted mutation"
        }
    }

    private fun validatePullProtocol(
        previousCursor: String?,
        page: PullPage,
    ) {
        val ids = page.mutations.map { it.remoteMutationId }

        require(ids.size == ids.toSet().size) {
            "A pull page must not contain duplicate remote mutation IDs"
        }

        if (page.hasMore) {
            require(!page.nextCursor.isNullOrBlank()) {
                "hasMore=true requires a non-empty nextCursor"
            }

            require(page.nextCursor != previousCursor) {
                "Pull cursor must advance while hasMore=true"
            }
        }
    }
}
