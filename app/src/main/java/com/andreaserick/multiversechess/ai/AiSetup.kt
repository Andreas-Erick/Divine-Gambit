package com.andreaserick.multiversechess.ai

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Setting up a language model: the player's choice, a test message, the models a provider offers,
 * and the on-device models downloaded. Games only need [model]; the rest is for the AI settings
 * screen. Its work runs in [scope], the view model's, so it stops when the app does.
 */
class AiSetup(context: Context, private val scope: CoroutineScope) {
    private val store = AiStore(context)

    /** The player's AI choice. */
    var settings by mutableStateOf(store.load())
        private set

    /** The language model to use, if one is set up. */
    val model: LanguageModel? get() = store.model(settings)

    fun save(next: AiSettings) {
        if (next.deviceModel != settings.deviceModel || next.provider != settings.provider) {
            scope.launch { OnDeviceModel.release() }
        }
        settings = next
        store.save(next)
    }

    /** The answer to a short test message, or what went wrong; null while waiting. */
    var test by mutableStateOf<Result<String>?>(null)
        private set

    /** Waiting for the answer to a test message. */
    var testing by mutableStateOf(false)
        private set
    private var testJob: Job? = null

    /** Sends [settings]' model a short message, to check it works. */
    fun test(settings: AiSettings) {
        val model = store.model(settings) ?: return
        testJob?.cancel()
        test = null
        testing = true
        testJob = scope.launch {
            try {
                test = try {
                    Result.success(model.chat("You are a friendly chess player. Answer in one short sentence.", listOf(ChatMessage(false, "Say hello to your opponent."))))
                } catch (e: AiException) {
                    Result.failure(e)
                }
            } finally {
                testing = false
            }
        }
    }

    /** Forgets the test's answer, and stops waiting for one (the settings it was for have changed). */
    fun clearTest() {
        testJob?.cancel()
        test = null
        testing = false
    }

    /** Models offered by the Gemini API or a server, for the settings; or why they couldn't be listed. */
    var models by mutableStateOf<Result<List<String>>?>(null)
        private set

    fun listModels(settings: AiSettings) {
        models = null
        scope.launch {
            models = try {
                Result.success(
                    when (settings.provider) {
                        AiProvider.GEMINI -> GeminiModel.models(settings.geminiKey)
                        else -> ServerModel.models(settings.serverUrl, settings.serverKey.ifBlank { null })
                    },
                )
            } catch (e: AiException) {
                Result.failure(e)
            }
        }
    }

    /** On-device models downloaded (by file name). */
    var installed by mutableStateOf(installedFiles())
        private set

    private fun installedFiles(): Set<String> =
        store.modelsDir.listFiles { file -> file.name.endsWith(".litertlm") }?.map { it.name }?.toSet().orEmpty()

    /** Downloads in progress: bytes so far (null while checking the file), by file name. */
    var downloads by mutableStateOf<Map<String, Long?>>(emptyMap())
        private set

    /** Why a download failed, by file name. */
    var downloadErrors by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    private val downloadJobs = HashMap<String, Job>()

    fun download(model: DeviceModel) {
        if (model.file in downloads) return
        downloads = downloads + (model.file to 0L)
        downloadErrors = downloadErrors - model.file
        downloadJobs[model.file] = scope.launch {
            try {
                DeviceModels.download(model, store.modelsDir) { done ->
                    scope.launch { if (model.file in downloads) downloads = downloads + (model.file to done) }
                }
                installed = installedFiles()
                // A first model downloaded is the one to use.
                if (settings.deviceModel.isBlank()) save(settings.copy(deviceModel = model.file))
            } catch (e: IOException) {
                downloadErrors = downloadErrors + (model.file to (e.message ?: "The download failed."))
            } finally {
                downloads = downloads - model.file
                downloadJobs -= model.file
            }
        }
    }

    fun cancelDownload(model: DeviceModel) {
        downloadJobs[model.file]?.cancel()
    }

    fun delete(model: DeviceModel) {
        scope.launch {
            OnDeviceModel.release()
            withContext(Dispatchers.IO) {
                File(store.modelsDir, model.file).delete()
                File(store.modelsDir, model.file + ".part").delete()
            }
            installed = installedFiles()
            if (settings.deviceModel == model.file) save(settings.copy(deviceModel = installed.firstOrNull().orEmpty()))
        }
    }
}
