package com.bydmate.app.packaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Packaging guard (backlog B-05).
 *
 * The remote-command launcher exists in two copies that MUST stay identical:
 *  - `tools/start_voltflow_cmd.sh`                    — the source-of-truth launcher
 *  - `app/src/main/assets/start_voltflow_cmd.sh`      — the copy bundled into the APK
 *
 * App self-revival deploys the *asset* copy from the APK, not the `tools/` one, so if
 * the asset drifts the app can resurrect an old watchdog even after `tools/` is fixed
 * (see the 2026-06-19 CommandDaemon incident in docs/project-notes.md). This test fails
 * the build when the two copies diverge, keeping the sync off the manual checklist.
 */
class LauncherAssetSyncTest {

    @Test
    fun `bundled launcher asset matches the tools source-of-truth`() {
        val repoRoot = findRepoRoot()
        val toolsLauncher = File(repoRoot, TOOLS_PATH)
        val assetLauncher = File(repoRoot, ASSET_PATH)

        assertTrue("Missing $TOOLS_PATH at ${toolsLauncher.absolutePath}", toolsLauncher.isFile)
        assertTrue("Missing $ASSET_PATH at ${assetLauncher.absolutePath}", assetLauncher.isFile)

        assertEquals(
            "$ASSET_PATH is out of sync with $TOOLS_PATH — the APK would bundle a stale " +
                "launcher. Copy tools/start_voltflow_cmd.sh over the asset (or vice versa) " +
                "so both are identical. See docs/BACKLOG.md B-05.",
            toolsLauncher.readText(),
            assetLauncher.readText(),
        )
    }

    /** Walk up from the test working dir until we find the dir holding both launcher copies. */
    private fun findRepoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, TOOLS_PATH).isFile && File(dir, ASSET_PATH).isFile) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "Could not locate repo root containing $TOOLS_PATH and $ASSET_PATH " +
                "(started from ${System.getProperty("user.dir")})",
        )
    }

    private companion object {
        const val TOOLS_PATH = "tools/start_voltflow_cmd.sh"
        const val ASSET_PATH = "app/src/main/assets/start_voltflow_cmd.sh"
    }
}
