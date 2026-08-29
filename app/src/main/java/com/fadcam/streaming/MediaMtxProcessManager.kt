package com.fadcam.streaming

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/** Owns the lifecycle of the bundled MediaMTX process without coupling FadCam to its Go source. */
class MediaMtxProcessManager(private val context: Context) {
    private var process: Process? = null

    fun start(config: File): Boolean {
        if (process?.isAlive == true) return true
        val executable = File(context.applicationInfo.nativeLibraryDir, "libmediamtx.so")
        if (!executable.exists()) return false
        executable.setExecutable(true)
        process = ProcessBuilder(executable.absolutePath, config.absolutePath)
            .directory(context.filesDir)
            .redirectErrorStream(true)
            .start()
        return process?.isAlive == true
    }

    fun stop(timeoutSeconds: Long = 3): Boolean {
        val current = process ?: return true
        current.destroy()
        if (!current.waitFor(timeoutSeconds, TimeUnit.SECONDS)) current.destroyForcibly()
        process = null
        return !current.isAlive
    }

    fun isRunning(): Boolean = process?.isAlive == true
}
