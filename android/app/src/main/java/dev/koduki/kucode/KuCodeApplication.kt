package dev.koduki.kucode

import android.app.Application

/**
 * Application entry point installing crash/lifecycle hooks and reconciling any unfinished prior
 * sessions asynchronously without starting a new active session.
 *
 * Starting a real session is deferred to [MainActivity], [ServerKeepAliveService], or
 * [PosixRuntimeManager.startIfNeeded] so background [KuCodeDocumentsProvider] queries from the
 * Android Files app after a crash never overwrite `latest-ai-report.md` with an empty session.
 */
class KuCodeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SessionTelemetryManager.getInstance(this).initializeProcessHooks()
    }
}
