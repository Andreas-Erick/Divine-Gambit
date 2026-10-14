package com.andreaserick.multiversechess.ui

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.andreaserick.multiversechess.ai.AiProvider
import com.andreaserick.multiversechess.ai.AiSettings
import com.andreaserick.multiversechess.ai.DeviceModel
import com.andreaserick.multiversechess.ai.DeviceModels
import com.andreaserick.multiversechess.ai.GeminiModel

/** Everything the AI settings screen shows and does; see [AiSettingsScreen]. */
class AiSettingsState(
    val settings: AiSettings,
    val onChange: (AiSettings) -> Unit,
    val test: Result<String>?,
    val onTest: () -> Unit,
    val models: Result<List<String>>?,
    val onListModels: () -> Unit,
    val installed: Set<String>,
    val downloads: Map<String, Long?>,
    val downloadErrors: Map<String, String>,
    val onDownload: (DeviceModel) -> Unit,
    val onCancelDownload: (DeviceModel) -> Unit,
    val onDelete: (DeviceModel) -> Unit,
)

/**
 * Choosing the language model to play against and design puzzles: Gemini with an API key, a model
 * served from a computer, or one on the phone. Changes are saved as they're made.
 */
@Composable
fun AiSettingsScreen(state: AiSettingsState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val settings = state.settings
    val context = LocalContext.current
    var testing by remember { mutableStateOf(false) }
    LaunchedEffect(state.test) { if (state.test != null) testing = false }

    // Android 17 asks before an app reaches devices on the local network, such as a model server.
    // The request waits for the answer; if access is refused, it fails with an explanation.
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val localNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        afterPermission?.invoke()
        afterPermission = null
    }
    fun withLocalNetwork(action: () -> Unit) {
        if (settings.provider == AiProvider.SERVER && Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(context, LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) {
            afterPermission = action
            localNetwork.launch(LOCAL_NETWORK)
        } else {
            action()
        }
    }

    OnlinePage(onBack, modifier) {
        Text("AI opponent", style = MaterialTheme.typography.titleLarge)
        Text(
            "Play against a language model, and have it design puzzles for you. It chooses among the legal turns " +
                "the engine finds, so it can't break the rules, but it plays by its own judgement: often less " +
                "strongly than the built-in computer, and with something to say about it.",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.inkSoft,
        )
        for ((provider, label, detail) in PROVIDERS) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(settings.provider == provider, role = Role.RadioButton) {
                        state.onChange(settings.copy(provider = provider))
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = settings.provider == provider, onClick = null)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(label, style = MaterialTheme.typography.titleSmall)
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = Palette.inkSoft)
                }
            }
        }

        if (settings.provider != AiProvider.OFF) HorizontalDivider(color = Palette.presentEdge.copy(alpha = 0.6f))
        when (settings.provider) {
            AiProvider.OFF -> Unit
            AiProvider.GEMINI -> GeminiSettings(state)
            AiProvider.SERVER -> ServerSettings(state, onListModels = { withLocalNetwork(state.onListModels) })
            AiProvider.ON_DEVICE -> DeviceSettings(state)
        }

        if (settings.provider != AiProvider.OFF) {
            HorizontalDivider(color = Palette.presentEdge.copy(alpha = 0.6f))
            PrimaryPill(
                onClick = {
                    testing = true
                    withLocalNetwork(state.onTest)
                },
                enabled = settings.isReady && !testing,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (testing) "Waiting for an answer…" else "Test") }
            state.test?.let { result ->
                result.fold(
                    { Text("“$it”", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic) },
                    { Text(it.message ?: "It didn't work.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) },
                )
            }
            Text(
                when (settings.provider) {
                    AiProvider.GEMINI -> "Your API key is kept encrypted on this phone. The positions of your games are sent to Google to answer."
                    AiProvider.SERVER -> "The positions of your games are sent to your computer, and nowhere else."
                    else -> "Everything stays on this phone, and works offline."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Palette.inkSoft,
            )
        }
    }
}

private val PROVIDERS = listOf(
    Triple(AiProvider.OFF, "Off", "Play the built-in computer only."),
    Triple(AiProvider.GEMINI, "Gemini", "Google's models, with your own API key (free to get)."),
    Triple(AiProvider.SERVER, "A model on your computer", "LM Studio or Ollama, on your network."),
    Triple(AiProvider.ON_DEVICE, "A model on this phone", "Runs offline, once downloaded."),
)

private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

@Composable
private fun GeminiSettings(state: AiSettingsState) {
    val settings = state.settings
    val uri = LocalUriHandler.current
    SecretField("API key", settings.geminiKey) { state.onChange(settings.copy(geminiKey = it.trim())) }
    TextButton(onClick = { uri.openUri("https://aistudio.google.com/apikey") }) { Text("Get a free API key at Google AI Studio") }
    ModelChoice(state.models, state.onListModels, current = settings.geminiModel, enabled = settings.geminiKey.isNotBlank(), suggest = GeminiModel::suggested) {
        state.onChange(settings.copy(geminiModel = it))
    }
}

@Composable
private fun ServerSettings(state: AiSettingsState, onListModels: () -> Unit) {
    val settings = state.settings
    Text(
        "In LM Studio, open Developer, start the server and turn on \"Serve on Local Network\". For Ollama, start it " +
            "with OLLAMA_HOST=0.0.0.0. Then enter the computer's address and port.",
        style = MaterialTheme.typography.bodySmall,
        color = Palette.inkSoft,
    )
    OutlinedTextField(
        value = settings.serverUrl,
        onValueChange = { state.onChange(settings.copy(serverUrl = it.trim())) },
        label = { Text("Address") },
        placeholder = { Text("http://192.168.1.20:1234") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    SecretField("API key (if the server needs one)", settings.serverKey) { state.onChange(settings.copy(serverKey = it.trim())) }
    ModelChoice(state.models, onListModels, current = settings.serverModel, enabled = settings.serverUrl.isNotBlank(), suggest = { it.firstOrNull() }) {
        state.onChange(settings.copy(serverModel = it))
    }
}

/** The model to use, from those listed by the API or server (loaded on request). */
@Composable
private fun ModelChoice(
    listed: Result<List<String>>?,
    onList: () -> Unit,
    current: String,
    enabled: Boolean,
    suggest: (List<String>) -> String?,
    onChoose: (String) -> Unit,
) {
    var asked by remember { mutableStateOf(false) }
    val models = listed?.getOrNull()
    LaunchedEffect(models) {
        if (asked && models != null && current !in models) suggest(models)?.let(onChoose)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Model", style = MaterialTheme.typography.titleSmall)
            Text(current.ifBlank { "None chosen yet" }, style = MaterialTheme.typography.bodySmall, color = Palette.inkSoft)
        }
        SecondaryPill(onClick = {
            asked = true
            onList()
        }, enabled = enabled) { Text(if (asked && listed == null) "Loading…" else "Choose") }
    }
    if (asked) {
        listed?.fold(
            { list ->
                if (list.isEmpty()) Text("No models found.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                for (model in list.take(12)) {
                    Row(
                        Modifier.fillMaxWidth().selectable(model == current, role = Role.RadioButton) { onChoose(model) }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = model == current, onClick = null)
                        Text(model, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp))
                    }
                }
            },
            { Text(it.message ?: "The models couldn't be listed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) },
        )
    }
}

@Composable
private fun SecretField(label: String, value: String, onChange: (String) -> Unit) {
    var shown by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = { TextButton(onClick = { shown = !shown }) { Text(if (shown) "Hide" else "Show") } },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DeviceSettings(state: AiSettingsState) {
    val settings = state.settings
    val uri = LocalUriHandler.current
    Text(
        "Download a model to run on this phone. Models are large, and answer slowly on older phones.",
        style = MaterialTheme.typography.bodySmall,
        color = Palette.inkSoft,
    )
    for (model in DeviceModels.all) {
        val installed = model.file in state.installed
        val progress = state.downloads[model.file]
        val downloading = model.file in state.downloads
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth().selectable(settings.deviceModel == model.file, enabled = installed, role = Role.RadioButton) {
                    state.onChange(settings.copy(deviceModel = model.file))
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = settings.deviceModel == model.file, onClick = null, enabled = installed)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text("${model.name} · ${model.sizeText}", style = MaterialTheme.typography.titleSmall)
                    Text(model.description, style = MaterialTheme.typography.bodySmall, color = Palette.inkSoft)
                }
            }
            when {
                downloading -> {
                    if (progress == null) {
                        Text("Checking the download…", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = Palette.accent)
                    } else {
                        LinearProgressIndicator(
                            progress = { (progress.toFloat() / model.bytes).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = Palette.accent,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${progress / 1_000_000} of ${model.bytes / 1_000_000} MB", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { state.onCancelDownload(model) }) { Text("Pause") }
                        }
                    }
                }
                installed -> Row {
                    TextButton(onClick = { uri.openUri(model.page) }) { Text("About & license") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { state.onDelete(model) }) { Text("Delete") }
                }
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { uri.openUri(model.page) }) { Text("About & license") }
                    Spacer(Modifier.weight(1f))
                    SecondaryPill(onClick = { state.onDownload(model) }) { Text("Download") }
                }
            }
            state.downloadErrors[model.file]?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
