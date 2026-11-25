package dev.koduki.kucode

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe, atomic status & bounded log store for the embedded POSIX runtime.
 *
 * All status fields (lifecycle state, progress, port, token, workspace path, proot readiness)
 * live inside a single immutable [RuntimeState] held in an [AtomicReference], preventing
 * callers on the UI or WebView bridge threads from ever observing half-updated state or
 * performing filesystem I/O when reading snapshots.
 */
internal class RuntimeStatusStore(
    initialWorkspacePath: String = "",
    initialProotReady: Boolean = false,
    private val maxLogLines: Int = DEFAULT_MAX_LOG_LINES,
    private val appName: String = "KuCode",
    private val packageName: String = "dev.koduki.kucode",
) {
    data class RuntimeState(
        val state: PosixRuntimeManager.State = PosixRuntimeManager.State.IDLE,
        val message: String = "起動待機中...",
        val progress: Int = 0,
        val port: Int = 0,
        val editorToken: String? = null,
        val workspacePath: String = "",
        val prootReady: Boolean = false,
    )

    private val current = AtomicReference(
        RuntimeState(
            workspacePath = initialWorkspacePath,
            prootReady = initialProotReady,
        ),
    )

    @Volatile
    private var listener: ((PosixRuntimeManager.StatusSnapshot) -> Unit)? = null

    private val logLock = Any()
    private val logs = ArrayDeque<String>()

    fun setListener(newListener: ((PosixRuntimeManager.StatusSnapshot) -> Unit)?) {
        listener = newListener
    }

    fun currentState(): RuntimeState = current.get()

    fun snapshot(hasAllFilesAccess: Boolean): PosixRuntimeManager.StatusSnapshot =
        toSnapshot(current.get(), hasAllFilesAccess)

    fun editorLaunchUrl(): String? {
        val state = current.get()
        val token = state.editorToken
        if (state.state != PosixRuntimeManager.State.READY || state.port <= 0 || token.isNullOrEmpty()) {
            return null
        }
        return "http://127.0.0.1:${state.port}/?folder=${PosixRuntimeConfig.GUEST_WORKSPACE}&tkn=$token"
    }

    fun update(
        hasAllFilesAccess: Boolean,
        notify: Boolean = true,
        transform: (RuntimeState) -> RuntimeState,
    ): PosixRuntimeManager.StatusSnapshot {
        val updated = current.updateAndGet(transform)
        val snapshot = toSnapshot(updated, hasAllFilesAccess)
        if (notify) {
            listener?.invoke(snapshot)
        }
        return snapshot
    }

    fun emitCurrent(hasAllFilesAccess: Boolean) {
        listener?.invoke(snapshot(hasAllFilesAccess))
    }

    fun appendLog(line: String) {
        synchronized(logLock) {
            logs.addLast(line)
            while (logs.size > maxLogLines) {
                logs.removeFirst()
            }
        }
    }

    fun getLogsText(): String = synchronized(logLock) {
        logs.joinToString("\n")
    }

    fun clearLogs() {
        synchronized(logLock) {
            logs.clear()
        }
    }

    private fun toSnapshot(
        state: RuntimeState,
        hasAllFilesAccess: Boolean,
    ): PosixRuntimeManager.StatusSnapshot {
        val url = if (state.state == PosixRuntimeManager.State.READY && state.port > 0) {
            "http://127.0.0.1:${state.port}/?folder=${PosixRuntimeConfig.GUEST_WORKSPACE}"
        } else {
            ""
        }
        return PosixRuntimeManager.StatusSnapshot(
            state = state.state,
            message = state.message,
            progress = state.progress,
            serverUrl = url,
            port = state.port,
            hasAllFilesAccess = hasAllFilesAccess,
            workspacePath = state.workspacePath,
            prootReady = state.prootReady,
            appName = appName,
            packageName = packageName,
        )
    }

    companion object {
        internal const val DEFAULT_MAX_LOG_LINES = 300
    }
}
