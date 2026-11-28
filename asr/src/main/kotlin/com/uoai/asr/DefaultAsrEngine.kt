package com.uoai.asr

import com.uoai.asr.runtime.AsrDecodeParams
import com.uoai.asr.runtime.AsrRuntime
import com.uoai.asr.runtime.NativeBridgeableAbortSignal
import com.uoai.core.resources.ComputeResourceCoordinator
import com.uoai.core.resources.ExecutionGuardResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class AsrQueueLimits(
    val maxQueued: Int = 16,
    val maxPendingPerClient: Int = 4,
    val recentKept: Int = 50,
    /** Age promotion interval preventing starvation of lower-priority clients. */
    val ageBoostIntervalMs: Long = 15_000L,
)

/**
 * Centralized shared ASR scheduler serving multiple Android client apps over a single loaded
 * Whisper model instance.
 *
 * - Runs exactly 1 native transcription at a time (`concurrency = 1`) to protect native state and RAM.
 * - Priority + FIFO ordering with age promotion.
 * - Lazy loads the selected model on first request when enabled.
 * - Cooperatively aborts native `whisper.cpp` execution on cancellation or client disconnect.
 */
class DefaultAsrEngine(
    private val manager: AsrModelManager,
    private val runtime: AsrRuntime,
    private val settingsRepository: AsrSettingsRepository,
    private val scope: CoroutineScope,
    private val recorder: AsrRequestRecorder = AsrRequestRecorder.None,
    private val limits: AsrQueueLimits = AsrQueueLimits(),
    private val resourceCoordinator: ComputeResourceCoordinator? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : AsrEngine {

    private class Ticket(
        val request: AsrRequest,
        val resolvedModelId: String,
        val seq: Long,
        val createdAt: Long,
    ) {
        val channel = Channel<AsrStreamEvent>(Channel.UNLIMITED)
        val abortSignal = NativeBridgeableAbortSignal()
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
    private val _queueState = MutableStateFlow(AsrQueueSnapshot())

    override val modelStatus: StateFlow<AsrModelStatus> get() = manager.status
    override val queueState: StateFlow<AsrQueueSnapshot> = _queueState.asStateFlow()

    init {
        scope.launch { worker() }
    }

    override fun stream(request: AsrRequest): Flow<AsrStreamEvent> = flow {
        validateRequest(request)
        val resolvedModel = resolveAndCheckModel(request.requestedModel)
        checkThermalGuard()

        // Lazy load if configured and installed
        if (!runtime.isLoaded) {
            val loaded = manager.ensureLoaded()
            if (!loaded || !runtime.isLoaded) {
                throw AsrModelNotReadyException(
                    "ASR model '${resolvedModel.displayName}' is not ready (state=${manager.status.value.state}). Install or load it in UOAI Provider.",
                )
            }
        }

        val ticket = enqueue(request, resolvedModel.id)
        try {
            for (ev in ticket.channel) {
                emit(ev)
            }
        } finally {
            if (!ticket.finished) {
                cancelTicket(ticket)
            }
        }
    }

    override suspend fun transcribe(request: AsrRequest): AsrResult {
        var finalResult: AsrResult? = null
        stream(request).collect { event ->
            if (event is AsrStreamEvent.Completed) {
                finalResult = event.result
            }
        }
        return finalResult ?: throw IllegalStateException("ASR stream finished without a result")
    }

    override fun cancel(requestId: String): Boolean {
        val t = tickets[requestId] ?: return false
        return cancelTicket(t)
    }

    override fun cancelAllForClient(clientId: String): Int {
        var count = 0
        for (t in tickets.values.toList()) {
            if (t.request.clientId == clientId && cancelTicket(t)) {
                count++
            }
        }
        return count
    }

    private fun validateRequest(r: AsrRequest) {
        require(r.audio.samples.isNotEmpty()) { "Audio samples must not be empty" }
        require(r.temperature in 0f..1f) { "temperature must be within 0..1" }
        val lang = r.language?.trim()?.lowercase()
        if (!lang.isNullOrEmpty() && !AsrModelCatalog.isValidLanguageSpec(lang)) {
            throw IllegalArgumentException("Unsupported language code '$lang'")
        }
        if (r.prompt != null && r.prompt.length > 512) {
            throw IllegalArgumentException("prompt exceeds 512 characters")
        }
    }

    private fun resolveAndCheckModel(requested: String?): AsrModelPreset {
        val status = manager.status.value
        val activePreset = status.activePreset
        val req = requested?.trim()?.lowercase()
        if (req.isNullOrEmpty() || req == "auto" || req == "default" || req == "whisper-1") {
            return activePreset
        }
        val matched = AsrModelCatalog.resolveOrNull(req)
            ?: throw IllegalArgumentException("Unknown ASR model '$requested'")
        if (matched.profile != activePreset.profile) {
            throw AsrModelMismatchException(
                "Requested ASR model '${matched.id}' does not match active profile '${activePreset.id}'. Use model='auto' or switch the active profile.",
            )
        }
        return activePreset
    }

    private fun checkThermalGuard() {
        val guard = resourceCoordinator?.canStartAsrExecution()
        if (guard is ExecutionGuardResult.ThermalThrottled) {
            throw AsrThermalThrottledException(guard.message)
        }
    }

    private fun enqueue(request: AsrRequest, modelId: String): Ticket {
        val t = Ticket(request, modelId, seq.getAndIncrement(), clock())
        synchronized(queue) {
            if (tickets.containsKey(request.requestId)) throw IllegalArgumentException("Duplicate ASR request id")
            if (queue.size >= limits.maxQueued) throw AsrQueueFullException("ASR queue is full (${limits.maxQueued})")
            if (tickets.values.count { it.request.clientId == request.clientId } >= limits.maxPendingPerClient) {
                throw AsrQueueFullException("Too many pending ASR requests for this client (${limits.maxPendingPerClient})")
            }
            tickets[request.requestId] = t
            queue.add(t)
        }
        publish(t, AsrRequestStatus.QUEUED)
        wake.trySend(Unit)
        return t
    }

    private fun pollNextWithAgeBoost(): Ticket? = synchronized(queue) {
        if (queue.isEmpty()) return null
        val now = clock()
        var best: Ticket? = null
        var bestScore = Long.MIN_VALUE
        for (t in queue) {
            val ageBoost = if (limits.ageBoostIntervalMs > 0) (now - t.createdAt).coerceAtLeast(0L) / limits.ageBoostIntervalMs else 0L
            val effPrio = t.request.priority.toLong() + ageBoost
            if (best == null || effPrio > bestScore || (effPrio == bestScore && t.seq < best.seq)) {
                best = t
                bestScore = effPrio
            }
        }
        if (best != null) queue.remove(best)
        best
    }

    private fun cancelTicket(t: Ticket): Boolean {
        t.cancelRequested = true
        t.abortSignal.abort()
        val removed = synchronized(queue) { queue.remove(t) }
        if (removed) {
            t.finished = true
            tickets.remove(t.request.requestId)
            t.channel.close(AsrRequestCancelledException(t.request.requestId))
            publish(t, AsrRequestStatus.CANCELLED)
            return true
        }
        val j = t.job
        if (t.running && !t.finished) {
            j?.cancel()
            return true
        }
        return false
    }

    private suspend fun worker() {
        for (ignored in wake) {
            while (true) {
                val t = pollNextWithAgeBoost() ?: break
                runTicket(t)
            }
        }
    }

    private suspend fun runTicket(t: Ticket) {
        if (t.cancelRequested) {
            t.finished = true
            tickets.remove(t.request.requestId)
            t.channel.close(AsrRequestCancelledException(t.request.requestId))
            publish(t, AsrRequestStatus.CANCELLED)
            return
        }

        t.running = true
        val started = clock()
        t.startedAt = started
        publish(t, AsrRequestStatus.RUNNING)
        manager.markTranscribing(true)

        var outcome = AsrRequestStatus.COMPLETED
        var error: String? = null
        val settings = settingsRepository.settings.value
        val activeProfile = manager.status.value.loadedProfile ?: settings.selectedProfile
        val activePreset = AsrModelCatalog.forProfile(activeProfile)
        val lang = t.request.language?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: settings.defaultLanguage

        val job = scope.launch {
            try {
                check(runtime.isLoaded) { "ASR model is not loaded" }
                checkThermalGuard()
                t.channel.trySend(AsrStreamEvent.Started(t.request.requestId, activePreset.id, lang))

                val output = if (resourceCoordinator != null) {
                    resourceCoordinator.withAsrExecution { coordThreads ->
                        val effectiveThreads = if (settings.maxThreads > 0) {
                            minOf(coordThreads, settings.maxThreads)
                        } else {
                            coordThreads
                        }
                        val params = AsrDecodeParams(
                            language = lang,
                            translate = t.request.translate || settings.translateToEnglish,
                            initialPrompt = t.request.prompt,
                            temperature = t.request.temperature,
                            threads = effectiveThreads.coerceAtLeast(1),
                            timestamps = t.request.timestamps && settings.timestampsEnabled,
                            singleSegment = t.request.singleSegment,
                        )
                        runtime.transcribe(t.request.audio.samples, params, t.abortSignal) { seg ->
                            t.channel.trySend(AsrStreamEvent.Segment(t.request.requestId, seg, lang))
                        }
                    }
                } else {
                    val threads = if (settings.maxThreads > 0) settings.maxThreads else 4
                    val params = AsrDecodeParams(
                        language = lang,
                        translate = t.request.translate || settings.translateToEnglish,
                        initialPrompt = t.request.prompt,
                        temperature = t.request.temperature,
                        threads = threads,
                        timestamps = t.request.timestamps && settings.timestampsEnabled,
                        singleSegment = t.request.singleSegment,
                    )
                    runtime.transcribe(t.request.audio.samples, params, t.abortSignal) { seg ->
                        t.channel.trySend(AsrStreamEvent.Segment(t.request.requestId, seg, lang))
                    }
                }

                val elapsed = (clock() - started).coerceAtLeast(1L)
                val result = AsrResult(
                    requestId = t.request.requestId,
                    modelId = activePreset.id,
                    profile = activeProfile,
                    text = output.text,
                    language = output.language,
                    durationSec = t.request.audio.durationSec,
                    segments = output.segments,
                    elapsedMs = elapsed,
                    languageConfidence = output.languageConfidence,
                )
                t.channel.trySend(AsrStreamEvent.Completed(result))
                t.channel.close()
            } catch (e: CancellationException) {
                outcome = AsrRequestStatus.CANCELLED
                t.channel.close(AsrRequestCancelledException(t.request.requestId))
            } catch (e: Throwable) {
                outcome = AsrRequestStatus.FAILED
                error = e.message ?: e::class.simpleName
                t.channel.close(e)
            }
        }
        t.job = job
        if (t.cancelRequested) {
            t.abortSignal.abort()
            job.cancel()
        }
        job.join()
        t.finished = true
        tickets.remove(t.request.requestId)
        manager.markTranscribing(false)
        publish(t, outcome, error)
    }

    private fun publish(t: Ticket, status: AsrRequestStatus, error: String? = null) {
        val terminal = status != AsrRequestStatus.QUEUED && status != AsrRequestStatus.RUNNING
        val rec = AsrRequestRecord(
            requestId = t.request.requestId,
            clientId = t.request.clientId,
            modelId = t.resolvedModelId,
            status = status,
            createdAt = t.createdAt,
            startedAt = t.startedAt,
            completedAt = if (terminal) clock() else null,
            durationSec = t.request.audio.durationSec,
            sourceBytes = t.request.sourceBytes,
            language = t.request.language,
            error = error,
        )
        recorder.onUpdate(rec)
        val queued = synchronized(queue) { queue.size }
        _queueState.update { s ->
            val recent = (listOf(rec) + s.recent.filter { it.requestId != rec.requestId }).take(limits.recentKept)
            AsrQueueSnapshot(
                queued = queued,
                runningRequestId = if (status == AsrRequestStatus.RUNNING) rec.requestId else s.runningRequestId.takeIf { it != rec.requestId },
                recent = recent,
            )
        }
    }
}

