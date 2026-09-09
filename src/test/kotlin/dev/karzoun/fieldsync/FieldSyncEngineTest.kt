package dev.karzoun.fieldsync

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

public class FieldSyncEngineTest {
    @Test
    public fun `accepted local mutation leaves queue and retains acknowledged version`(): Unit = runBlocking {
        val store = InMemorySyncStore()
        val key = EntityKey("jobs", "job-1")
        store.stageLocal(pending("m-1", key, """{"status":"done"}"""))

        val report = FieldSyncEngine(
            store,
            ScriptedTransport(
                pushHandler = { listOf(PushOutcome.Accepted(MutationId("m-1"), 7)) },
                pullPages = mutableListOf(PullPage(emptyList(), "c-1", false)),
            ),
        ).syncOnce()

        assertEquals(1, report.pushed)
        assertEquals(0, store.pendingCount())
        assertEquals(7, store.record(key)?.serverVersion)
        assertEquals("c-1", store.checkpoint())
    }

    @Test
    public fun `transport failure does not discard staged local mutation`(): Unit = runBlocking {
        val store = InMemorySyncStore()
        val key = EntityKey("assets", "asset-1")
        store.stageLocal(pending("m-1", key, """{"value":1}"""))

        val transport = object : SyncTransport {
            override suspend fun push(mutations: List<PendingMutation>): List<PushOutcome> = error("offline")
            override suspend fun pull(cursor: String?, limit: Int): PullPage = error("must not be called")
        }

        assertSuspendFailsWith<IllegalStateException> {
            FieldSyncEngine(store, transport).syncOnce()
        }

        assertEquals(1, store.pendingCount())
        assertNull(store.checkpoint())
        assertEquals("""{"value":1}""", store.record(key)?.payload)
    }

    @Test
    public fun `retryable push increments attempt and keeps mutation pending`(): Unit = runBlocking {
        val store = InMemorySyncStore()
        val key = EntityKey("assets", "asset-1")
        store.stageLocal(pending("m-1", key, """{"value":1}"""))

        val report = FieldSyncEngine(
            store,
            ScriptedTransport(
                pushHandler = { listOf(PushOutcome.RetryableFailure(MutationId("m-1"), "503")) },
                pullPages = mutableListOf(PullPage(emptyList(), null, false)),
            ),
        ).syncOnce()

        assertEquals(1, report.retryableFailures)
        assertEquals(1, store.pendingCount())
        assertEquals(1, store.pending(10).single().attempts)
    }

    @Test
    public fun `permanent push failure leaves active queue and enters failure bucket`(): Unit = runBlocking {
        val store = InMemorySyncStore()
        val key = EntityKey("assets", "asset-1")
        store.stageLocal(pending("m-1", key, """{"value":1}"""))

        val report = FieldSyncEngine(
            store,
            ScriptedTransport(
                pushHandler = { listOf(PushOutcome.PermanentFailure(MutationId("m-1"), "validation")) },
                pullPages = mutableListOf(PullPage(emptyList(), null, false)),
            ),
        ).syncOnce()

        assertEquals(1, report.permanentFailures)
        assertEquals(0, store.pendingCount())
        assertEquals(1, store.permanentFailureCount())
    }

    @Test
    public fun `remote page applies record and advances checkpoint`(): Unit = runBlocking {
        val store = InMemorySyncStore()
        val key = EntityKey("jobs", "job-2")
        val remote = RemoteMutation("r-1", key, MutationKind.UPSERT, """{"status":"assigned"}""", 4)

        val report = FieldSyncEngine(
            store,
            ScriptedTransport(
                pushHandler = { emptyList() },
                pullPages = mutableListOf(PullPage(listOf(remote), "cursor-4", false)),
            ),
        ).syncOnce()

        assertEquals(1, report.remoteSeen)
        assertEquals(1, report.remoteApplied)
        assertEquals("""{"status":"assigned"}""", store.record(key)?.payload)
        assertEquals(4, store.record(key)?.serverVersion)
        assertEquals("cursor-4", store.checkpoint())
    }

    @Test
    public fun `duplicate remote mutation is idempotent across later cursor`(): Unit = runBlocking {
        val store = InMemorySyncStore()
        val key = EntityKey("jobs", "job-2")
        val remote = RemoteMutation("r-1", key, MutationKind.UPSERT, """{"status":"assigned"}""", 4)

        FieldSyncEngine(
            store,
            ScriptedTransport({ emptyList() }, mutableListOf(PullPage(listOf(remote), "cursor-1", false))),
        ).syncOnce()

        FieldSyncEngine(
            store,
            ScriptedTransport({ emptyList() }, mutableListOf(PullPage(listOf(remote), "cursor-2", false))),
        ).syncOnce()

        assertEquals("""{"status":"assigned"}""", store.record(key)?.payload)
        assertEquals("cursor-2", store.checkpoint())
    }

    @Test
    public fun `local pending wins remote conflict without losing cursor progress`(): Unit = runBlocking {
        val store = InMemorySyncStore()
        val key = EntityKey("assets", "a-1")
        store.stageLocal(pending("m-local", key, """{"name":"local"}"""))
        val remote = RemoteMutation("r-1", key, MutationKind.UPSERT, """{"name":"server"}""", 9)

        val report = FieldSyncEngine(
            store,
            ScriptedTransport(
                pushHandler = { listOf(PushOutcome.RetryableFailure(MutationId("m-local"), "offline")) },
                pullPages = mutableListOf(PullPage(listOf(remote), "cursor-9", false)),
            ),
            conflictPolicy = ConflictPolicy.LocalPendingWins,
        ).syncOnce()

        assertEquals(1, report.conflictsKeptLocal)
        assertEquals("""{"name":"local"}""", store.record(key)?.payload)
        assertEquals(1, store.pendingCount())
        assertEquals("cursor-9", store.checkpoint())
    }

    @Test
    public fun `server wins conflict atomically drops local pending change`(): Unit = runBlocking {
        val store = InMemorySyncStore()
        val key = EntityKey("assets", "a-1")
        store.stageLocal(pending("m-local", key, """{"name":"local"}"""))
        val remote = RemoteMutation("r-1", key, MutationKind.UPSERT, """{"name":"server"}""", 9)

        FieldSyncEngine(
            store,
            ScriptedTransport(
                pushHandler = { listOf(PushOutcome.RetryableFailure(MutationId("m-local"), "offline")) },
                pullPages = mutableListOf(PullPage(listOf(remote), "cursor-9", false)),
            ),
            conflictPolicy = ConflictPolicy.ServerWins,
        ).syncOnce()

        assertEquals("""{"name":"server"}""", store.record(key)?.payload)
        assertEquals(9, store.record(key)?.serverVersion)
        assertEquals(0, store.pendingCount())
        assertEquals("cursor-9", store.checkpoint())
    }

    @Test
    public fun `pull cursor must advance while more pages remain`(): Unit = runBlocking {
        val store = InMemorySyncStore()
        val transport = ScriptedTransport(
            pushHandler = { emptyList() },
            pullPages = mutableListOf(
                PullPage(emptyList(), "same", true),
                PullPage(emptyList(), "same", true),
            ),
        )

        assertSuspendFailsWith<IllegalArgumentException> {
            FieldSyncEngine(store, transport, maxPullPages = 2).syncOnce()
        }
    }

    @Test
    public fun `push protocol rejects missing outcomes without mutating queue`(): Unit = runBlocking {
        val store = InMemorySyncStore()
        store.stageLocal(pending("m-1", EntityKey("assets", "asset-1"), """{"v":1}"""))
        store.stageLocal(pending("m-2", EntityKey("assets", "asset-2"), """{"v":2}"""))

        val transport = ScriptedTransport(
            pushHandler = { listOf(PushOutcome.Accepted(MutationId("m-1"), 1)) },
            pullPages = mutableListOf(),
        )

        assertSuspendFailsWith<IllegalArgumentException> {
            FieldSyncEngine(store, transport).syncOnce()
        }

        assertEquals(2, store.pendingCount())
    }

    private suspend inline fun <reified T : Throwable> assertSuspendFailsWith(
        crossinline block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("Expected ${T::class.simpleName}, caught ${error::class.simpleName}", error)
        }
        throw AssertionError("Expected ${T::class.simpleName} to be thrown")
    }

    private fun pending(id: String, key: EntityKey, payload: String): PendingMutation = PendingMutation(
        mutationId = MutationId(id),
        entity = key,
        kind = MutationKind.UPSERT,
        payload = payload,
        createdAtEpochMillis = 1,
    )

    private class ScriptedTransport(
        private val pushHandler: suspend (List<PendingMutation>) -> List<PushOutcome>,
        private val pullPages: MutableList<PullPage>,
    ) : SyncTransport {
        override suspend fun push(mutations: List<PendingMutation>): List<PushOutcome> = pushHandler(mutations)

        override suspend fun pull(cursor: String?, limit: Int): PullPage {
            check(pullPages.isNotEmpty()) { "No scripted pull page left" }
            return pullPages.removeAt(0)
        }
    }
}
