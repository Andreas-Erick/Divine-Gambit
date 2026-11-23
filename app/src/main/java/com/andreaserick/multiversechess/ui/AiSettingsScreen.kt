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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
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
    /** Waiting for the test's answer. */
    val testing: Boolean,
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
    val testing = state.testing

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
                    .heightIn(min = 48.dp)
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
                onClick = { withLocalNetwork(state.onTest) },
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
    val focusManager = LocalFocusManager.current
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
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
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
        val loading = asked && listed == null
        SecondaryPill(onClick = {
            asked = true
            onList()
        }, enabled = enabled && !loading) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Palette.accent)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (loading) "Loading…" else "Choose")
        }
    }
    if (asked) {
        var all by remember { mutableStateOf(false) }
        listed?.fold(
            { list ->
                if (list.isEmpty()) Text("No models found.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                // The first dozen, then all on request; the chosen one is always among those shown.
                val shown = if (all || list.size <= MODELS_SHOWN) list else list.take(MODELS_SHOWN).let { first -> if (current in list && current !in first) first + current else first }
                for (model in shown) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(model == current, role = Role.RadioButton) { onChoose(model) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = model == current, onClick = null)
                        Text(model, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp).weight(1f))
                    }
                }
            },
            { Text(it.message ?: "The models couldn't be listed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) },
        )
        val count = listed?.getOrNull()?.size ?: 0
        if (!all && count > MODELS_SHOWN) {
            TextButton(onClick = { all = true }) { Text("Show all $count models") }
        }
    }
}

/** How many listed models show before "Show all". */
private const val MODELS_SHOWN = 12

@Composable
private fun SecretField(label: String, value: String, onChange: (String) -> Unit) {
    var shown by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
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
                            Text(
                                "${(progress * 100 / model.bytes).coerceIn(0, 100)}% · ${progress / 1_000_000} of ${model.bytes / 1_000_000} MB",
                                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TABULAR_NUMS),
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { state.onCancelDownload(model) }) { Text("Pause") }
                        }
                    }
                }
                installed -> Row {
                    var confirmDelete by remember { mutableStateOf(false) }
                    TextButton(onClick = { uri.openUri(model.page) }) { Text("About & license") }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { confirmDelete = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Delete") }
                    if (confirmDelete) {
                        AlertDialog(
                            onDismissRequest = { confirmDelete = false },
                            confirmButton = {
                                DestructiveTextButton("Delete") {
                                    confirmDelete = false
                                    state.onDelete(model)
                                }
                            },
                            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep it") } },
                            title = { Text("Delete ${model.name}?") },
                            text = { Text("To play against it again, you'll need to download it again (${model.sizeText}).") },
                        )
                    }
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
