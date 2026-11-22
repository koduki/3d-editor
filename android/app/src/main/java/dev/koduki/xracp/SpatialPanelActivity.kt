package dev.koduki.xracp

/**
 * Independent Android XR Spatial Panel Activity launched adjacent to the main Workbench
 * (e.g. detaching the AI Agent panel or Console into its own movable 3D window).
 */
class SpatialPanelActivity : WorkbenchActivity() {
    override fun initialFocusPane(): String {
        return intent?.getStringExtra(EXTRA_FOCUS_PANE) ?: "ai"
    }
}
