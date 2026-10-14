package com.andreaserick.multiversechess.ai

import android.os.StatFs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request

/** A model the app can download to run on the phone, from Hugging Face. */
data class DeviceModel(
    val name: String,
    val description: String,
    val repository: String,
    val file: String,
    val bytes: Long,
    val sha256: String,
) {
    val url: String get() = "https://huggingface.co/$repository/resolve/main/$file"
    val page: String get() = "https://huggingface.co/$repository"
    val sizeText: String get() = if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1e9) else "${bytes / 1_000_000} MB"
}

/** The on-device models on offer (both Apache 2.0), and their download. */
object DeviceModels {
    val all = listOf(
        DeviceModel(
            name = "Gemma 4 E2B",
            description = "Google's small open model. Plays and designs puzzles best of the two, but needs a recent phone " +
                "with plenty of memory, and takes a while to answer.",
            repository = "litert-community/gemma-4-E2B-it-litert-lm",
            file = "gemma-4-E2B-it.litertlm",
            bytes = 2_588_147_712,
            sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        ),
        DeviceModel(
            name = "Qwen3 0.6B",
            description = "A tiny model: quick, and fine on most phones, but its choices are close to random and its " +
                "puzzles rarely work out.",
            repository = "litert-community/Qwen3-0.6B",
            file = "Qwen3-0.6B.litertlm",
            bytes = 613_722_264,
            sha256 = "c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108",
        ),
    )

    fun byFile(file: String): DeviceModel? = all.firstOrNull { it.file == file }

    /**
     * Downloads [model] into [dir], resuming an earlier partial download, then checks its SHA-256.
     * [progress] gets the bytes so far, and null while checking. Cancelling the coroutine stops it,
     * keeping what was downloaded for next time.
     */
    suspend fun download(model: DeviceModel, dir: File, progress: (Long?) -> Unit): File = withContext(Dispatchers.IO) {
        val target = File(dir, model.file)
        val partial = File(dir, model.file + ".part")
        val needed = model.bytes - partial.length()
        if (StatFs(dir.path).availableBytes < needed + FREE_MARGIN) {
            throw IOException("Not enough free space: ${model.name} needs ${model.sizeText}.")
        }
        val request = Request.Builder().url(model.url).apply { if (partial.length() > 0) header("Range", "bytes=${partial.length()}-") }.build()
        aiHttp.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("The download failed (${response.code}).")
            // A server that ignores the range sends the whole file again.
            val append = response.code == 206
            FileOutputStream(partial, append).use { out ->
                var done = if (append) partial.length() else 0L
                val buffer = ByteArray(1 shl 16)
                val input = response.body.byteStream()
                var lastReport = 0L
                while (true) {
                    ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    done += read
                    if (done - lastReport >= REPORT_EVERY) {
                        progress(done)
                        lastReport = done
                    }
                }
            }
        }
        progress(null)
        if (partial.length() != model.bytes || sha256(partial) != model.sha256) {
            partial.delete()
            throw IOException("The downloaded file was damaged; please try again.")
        }
        if (!partial.renameTo(target)) throw IOException("The model couldn't be saved.")
        target
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private const val FREE_MARGIN = 200_000_000L
    private const val REPORT_EVERY = 4_000_000L
}
