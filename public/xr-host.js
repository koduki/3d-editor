/**
 * Android 15 (API 35) & Android XR Native Host Bridge abstraction (Phase 2 ready).
 *
 * When running inside a standard browser (Chrome on XREAL AURA / Meta Quest Browser),
 * this provides safe web fallbacks. When wrapped in a Phase 2 Android XR Kotlin / Jetpack XR
 * container exposing `window.XRHostBridge`, it enables:
 * - Intercepting desktop shortcuts (Ctrl+P, Ctrl+W, Ctrl+K, Ctrl+`) before browser chrome
 * - Detaching panes (Editor / AI / Console) into independent Android XR Spatial Panels
 * - Querying Android 15 AVF Linux Terminal / local runtime status
 */

export function createXrHostBridge() {
  const nativeBridge = typeof window !== 'undefined' ? window.XRHostBridge || null : null;

  const isAndroid15Plus =
    typeof navigator !== 'undefined' && /Android 1[5-9]|Android [2-9]\d/i.test(navigator.userAgent || '');

  return {
    isNativeHost: Boolean(nativeBridge),
    isAndroid15Plus,

    getCapabilities() {
      if (nativeBridge && typeof nativeBridge.getCapabilities === 'function') {
        try {
          return JSON.parse(nativeBridge.getCapabilities());
        } catch {
          // fallback
        }
      }
      return {
        nativeHost: Boolean(nativeBridge),
        spatialPanels: Boolean(nativeBridge?.openSpatialPanel),
        shortcutCapture: Boolean(nativeBridge?.registerShortcuts),
        android15Avf: isAndroid15Plus,
      };
    },

    registerDesktopShortcuts(shortcuts) {
      if (nativeBridge && typeof nativeBridge.registerShortcuts === 'function') {
        try {
          nativeBridge.registerShortcuts(JSON.stringify(shortcuts));
        } catch {
          // ignore
        }
      }
    },

    detachSpatialPanel(paneId) {
      if (nativeBridge && typeof nativeBridge.openSpatialPanel === 'function') {
        try {
          nativeBridge.openSpatialPanel(String(paneId));
          return true;
        } catch {
          return false;
        }
      }
      // Browser fallback: open standalone spatial window for multi-window XR workspaces
      if (typeof window !== 'undefined') {
        const url = new URL(window.location.href);
        url.searchParams.set('focusPane', paneId);
        window.open(url.toString(), `xr-acp-${paneId}`, 'noopener');
        return true;
      }
      return false;
    },

    notifyWindowTitle(title) {
      if (typeof document !== 'undefined') {
        document.title = title;
      }
      if (nativeBridge && typeof nativeBridge.setWindowTitle === 'function') {
        try {
          nativeBridge.setWindowTitle(String(title));
        } catch {
          // ignore
        }
      }
    },
  };
}
