package dev.pocketrun

import android.app.Application
import dev.pocketrun.runtime.python.PythonRuntime

/**
 * Starts CPython in the background as soon as the process comes up, so by the
 * time the user has activated and opened a project the interpreter is warm.
 */
class PocketRunApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PythonRuntime.initAsync(this)
    }
}
