package dev.koduki.kucode

import android.app.Application

/**
 * Application entry point initializing the per-launch flight recorder ([SessionTelemetryManager])
 * at process creation so startup, crash/ANR/LMKD exit reconciliation, and lifecycle telemetry
 * cover the entire application lifetime from birth to termination.
 */
class KuCodeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SessionTelemetryManager.getInstance(this).initializeOnAppStart()
    }
}
