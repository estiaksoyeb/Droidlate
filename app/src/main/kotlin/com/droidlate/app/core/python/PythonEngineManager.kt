package com.droidlate.app.core.python

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.droidlate.app.core.network.DroidlateApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

sealed class EngineState {
    object Idle : EngineState()
    object Initializing : EngineState()
    object Ready : EngineState()
    data class Error(val message: String) : EngineState()
}

class PythonEngineManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "PythonEngineManager"
        private const val DEFAULT_PORT = 5000

        @Volatile
        private var INSTANCE: PythonEngineManager? = null

        fun getInstance(context: Context): PythonEngineManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PythonEngineManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val _engineState = MutableStateFlow<EngineState>(EngineState.Idle)
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    val apiClient = DroidlateApiClient("http://127.0.0.1:$DEFAULT_PORT/")
    private var isServerThreadRunning = false

    /**
     * Initializes Chaquopy and starts the embedded Flask server on localhost:5000.
     */
    suspend fun startEngine(initialResDir: String? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            // 1. Start Chaquopy Python runtime if not started
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context))
            }

            val py = Python.getInstance()

            // Ensure Python environment has writable cache & home directories on Android
            val osModule = py.getModule("os")
            val environ = osModule.get("environ")
            val appCacheDir = context.cacheDir.absolutePath
            environ?.callAttr("__setitem__", "XDG_CACHE_HOME", "$appCacheDir/python_cache")
            environ?.callAttr("__setitem__", "HOME", appCacheDir)

            val serverModule = py.getModule("droidlate.web.server")
            if (initialResDir != null) {
                serverModule.put("RES_DIR", initialResDir)
                serverModule.put("SOURCE_XML", null)
                serverModule.put("TARGET_XML", null)
                serverModule.put("IS_SINGLE_FILE_MODE", false)
            }

            // 2. Start Flask server on background daemon thread if not already running
            if (!isServerThreadRunning) {
                isServerThreadRunning = true
                val serverThread = Thread {
                    try {
                        Log.i(TAG, "Starting embedded Python Flask server on 127.0.0.1:$DEFAULT_PORT...")
                        serverModule.callAttr("start_web_server", initialResDir, null, null, DEFAULT_PORT)
                    } catch (e: Exception) {
                        Log.e(TAG, "Embedded Flask server encountered an error: ${e.message}", e)
                    }
                }
                serverThread.isDaemon = true
                serverThread.name = "Droidlate-Python-Server"
                serverThread.start()
            }

            // 3. Wait for localhost server to become responsive
            var attempts = 0
            val maxAttempts = 30 // ~6 seconds total
            var serverUp = false

            while (attempts < maxAttempts) {
                if (apiClient.isServerAlive()) {
                    serverUp = true
                    break
                }
                delay(200)
                attempts++
            }

            if (serverUp) {
                if (initialResDir != null) {
                    setWorkspace(initialResDir)
                }
                _engineState.value = EngineState.Ready
                Log.i(TAG, "Embedded Python Flask server is online and ready.")
                true
            } else {
                _engineState.value = EngineState.Error("Python server failed to respond on 127.0.0.1:$DEFAULT_PORT")
                false
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Python engine: ${e.message}", e)
            _engineState.value = EngineState.Error("Failed to initialize Python: ${e.message}")
            false
        }
    }


    /**
     * Dynamically switches the active resource directory in the Python runtime
     * without modifying the upstream Droidlate codebase or restarting the server.
     */
    fun setWorkspace(resDirPath: String) {
        try {
            val py = Python.getInstance()
            val serverModule = py.getModule("droidlate.web.server")
            serverModule.put("RES_DIR", resDirPath)
            serverModule.put("SOURCE_XML", null)
            serverModule.put("TARGET_XML", null)
            serverModule.put("IS_SINGLE_FILE_MODE", false)
            Log.i(TAG, "Switched active RES_DIR to: $resDirPath")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update Python RES_DIR workspace: ${e.message}", e)
        }
    }

    /**
     * Ensures the Python server is running and the active workspace RES_DIR
     * is set in Python memory before any HTTP queries are executed.
     */
    suspend fun prepareWorkspace(resDirPath: String): Boolean = withContext(Dispatchers.IO) {
        val started = startEngine(resDirPath)
        if (started) {
            setWorkspace(resDirPath)
            delay(100) // Brief pause to allow Python runtime to stabilize
            true
        } else {
            false
        }
    }
}

