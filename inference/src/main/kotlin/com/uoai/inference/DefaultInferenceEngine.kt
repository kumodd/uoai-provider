package com.uoai.inference

import com.uoai.core.resources.ComputeResourceCoordinator
import com.uoai.inference.runtime.InferenceRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class QueueLimits(val maxQueued: Int = 16, val maxPendingPerClient: Int = 4, val recentKept: Int = 50)

/**
 * Centralized scheduler + engine (FR-03/FR-04). One worker coroutine drains a priority queue and runs
 * exactly one generation at a time on the single shared runtime.
 */
class DefaultInferenceEngine(
    private val manager: ModelManager,
    private val runtime: InferenceRuntime,
    private val scope: CoroutineScope,
    private val recorder: RequestRecorder = RequestRecorder.None,
    private val limits: QueueLimits = QueueLimits(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val resourceCoordinator: ComputeResourceCoordinator? = null,
) : InferenceEngine {

    private class Ticket(val request: InferenceRequest, val seq: Long, val createdAt: Long) {
        val channel = Channel<String>(Channel.UNLIMITED)
        @Volatile var job: Job? = null
        @Volatile var cancelRequested = false
        @Volatile var finished = false
        @Volatile var startedAt: Long? = null
        @Volatile var running = false
    }

    private val seq = AtomicLong()
    private val queue = PriorityQueue<Ticket>(compareByDescending<Ticket> { it.request.priority }.thenBy { it.seq })
    private val tickets = ConcurrentHashMap<String, Ticket>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val _queueState = MutableStateFlow(QueueSnapshot())

    override val modelStatus: StateFlow<ModelStatus> get() = manager.status
    override val queueState: StateFlow<QueueSnapshot> = _queueState.asStateFlow()

    init { scope.launch { worker() } }

    override fun stream(request: InferenceRequest): Flow<String> = flow {
        validate(request)
        val state = manager.status.value.state
        if (!runtime.isLoaded || (state != ModelState.READY && state != ModelState.GENERATING))
            throw ModelNotReadyException("Model is not ready (state=$state)")
        val ticket = enqueue(request)
        try {
            for (token in ticket.channel) emit(token) // rethrows the close cause (failure / cancellation)
        } finally {
            if (!ticket.finished) cancelTicket(ticket) // collector went away
        }
    }

    override suspend fun generate(request: InferenceRequest): String = stream(request).toList().joinToString("")

    override fun cancel(requestId: String): Boolean {
        val t = tickets[requestId] ?: return false
        return cancelTicket(t)
    }

    // ---- queue management ----

    private fun enqueue(request: InferenceRequest): Ticket {
        val t = Ticket(request, seq.getAndIncrement(), clock())
        synchronized(queue) {
            if (tickets.containsKey(request.requestId)) throw IllegalArgumentException("duplicate request id")
            if (queue.size >= limits.maxQueued) throw QueueFullException("Inference queue is full (${limits.maxQueued})")
            if (tickets.values.count { it.request.clientId == request.clientId } >= limits.maxPendingPerClient)
                throw QueueFullException("Too many pending requests for this client (${limits.maxPendingPerClient})")
            tickets[request.requestId] = t
            queue.add(t)
        }
        publish(t, RequestStatus.QUEUED)
        wake.trySend(Unit)
        return t
    }

    private fun cancelTicket(t: Ticket): Boolean {
        t.cancelRequested = true
        val removed = synchronized(queue) { queue.remove(t) }
        if (removed) {
            t.finished = true
            tickets.remove(t.request.requestId)
            t.channel.close(RequestCancelledException(t.request.requestId))
            publish(t, RequestStatus.CANCELLED)
            return true
        }
        val j = t.job
        if (t.running && !t.finished) { j?.cancel(); return true } // worker sees cancelRequested if job not yet set
        return false
    }

    private suspend fun worker() {
        for (ignored in wake) {
            while (true) {
                val t = synchronized(queue) { queue.poll() } ?: break
                run(t)
            }
        }
    }

    private suspend fun run(t: Ticket) {
        t.running = true
        t.startedAt = clock()
        publish(t, RequestStatus.RUNNING)
        manager.markGenerating(true)
        var outcome = RequestStatus.COMPLETED
        var error: String? = null
        val onCpu = runtime.loadedConfig?.preferredBackend == AcceleratorBackend.CPU
        val job = scope.launch {
            try {
                check(runtime.isLoaded) { "Model not loaded" }
                val audio = t.request.audioWavBytes
                val execute: suspend () -> Unit = {
                    val stream = if (audio != null) {
                        runtime.generateWithAudio(t.request.messages, audio, t.request.params)
                    } else {
                        runtime.generateMessages(t.request.messages, t.request.params)
                    }
                    stream.collect { t.channel.trySend(it) }
                }
                if (resourceCoordinator != null) {
                    resourceCoordinator.withLlmExecution(onCpu = onCpu) { execute() }
                } else {
                    execute()
                }
                t.channel.close()
            } catch (e: CancellationException) {
                outcome = RequestStatus.CANCELLED
                t.channel.close(RequestCancelledException(t.request.requestId))
            } catch (e: Throwable) {
                outcome = RequestStatus.FAILED; error = e.message ?: e::class.simpleName
                t.channel.close(e)
            }
        }
        t.job = job
        if (t.cancelRequested) job.cancel()
        job.join()
        t.finished = true
        tickets.remove(t.request.requestId)
        manager.markGenerating(false)
        publish(t, outcome, error)
    }

    private fun publish(t: Ticket, status: RequestStatus, error: String? = null) {
        val terminal = status != RequestStatus.QUEUED && status != RequestStatus.RUNNING
        val rec = RequestRecord(
            t.request.requestId, t.request.clientId, t.request.modelId, status, t.createdAt,
            t.startedAt, if (terminal) clock() else null, error,
        )
        recorder.onUpdate(rec, t.request)
        val queued = synchronized(queue) { queue.size }
        _queueState.update { s ->
            val recent = (listOf(rec) + s.recent.filter { it.requestId != rec.requestId }).take(limits.recentKept)
            QueueSnapshot(queued, if (status == RequestStatus.RUNNING) rec.requestId else s.runningRequestId.takeIf { it != rec.requestId }, recent)
        }
    }

    private fun validate(r: InferenceRequest) {
        require(r.messages.isNotEmpty()) { "messages must not be empty" }
        require(r.messages.any { it.role == "user" }) { "at least one user message is required" }
        require(r.params.temperature in 0f..2f) { "temperature must be within 0..2" }
        require(r.params.maxTokens in 1..InferenceSettings.MAX_TOKENS_32K) {
            "max_tokens must be within 1..${InferenceSettings.MAX_TOKENS_32K}"
        }
    }
}
