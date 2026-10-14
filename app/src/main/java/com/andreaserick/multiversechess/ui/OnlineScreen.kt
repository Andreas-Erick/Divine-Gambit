package com.andreaserick.multiversechess.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andreaserick.multiversechess.online.Connecting
import com.andreaserick.multiversechess.online.Invitation
import com.andreaserick.multiversechess.online.OnlineStatus
import com.andreaserick.multiversechess.online.Ticket

/** The host's wait for a friend: the invitation to send, until the friend opens it. */
@Composable
fun LobbyScreen(status: OnlineStatus, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    OnlinePage(onBack, modifier) {
        Text("Invite a friend", style = MaterialTheme.typography.titleLarge)
        Text(
            "Send this link only to the friend you want to play: whoever opens it first takes " +
                "the seat. When they open it, the game begins. If it opens in a browser, they can " +
                "choose Join a friend in the app and type the code.",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.inkSoft,
        )
        Text(
            status.ticket.displayCode,
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 3.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        Text(status.link, style = MaterialTheme.typography.bodySmall, color = Palette.inkSoft, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        PrimaryPill(onClick = { share(context, status.link) }, modifier = Modifier.fillMaxWidth()) { Text("Share link") }
        SecondaryPill(onClick = { copy(context, status.link) }, modifier = Modifier.fillMaxWidth()) { Text("Copy link") }
        Text(ticketSummary(status.ticket), style = MaterialTheme.typography.bodySmall, color = Palette.inkSoft)
        when {
            status.problem != null -> Text(status.problem, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            else -> Waiting(if (status.connected) "Waiting for your friend…" else "Connecting…")
        }
    }
}

/** An invitation opened: who invites this player, to what, and their name to join with. */
@Composable
fun InvitationScreen(invitation: Invitation, playerName: String, onJoin: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var name by rememberSaveable { mutableStateOf(playerName) }
    val valid = Ticket.parseName(name)
    OnlinePage(onBack, modifier) {
        Text("${invitation.hostName} invites you to play", style = MaterialTheme.typography.titleLarge)
        val limit = invitation.timelineLimit?.let { "up to $it timelines each" } ?: "unlimited timelines"
        Text(
            "You play ${invitation.hostSide.opponent.displayName} · ${invitation.variant.displayName} · $limit",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.inkSoft,
        )
        NameField(name, onChange = { name = it })
        PrimaryPill(onClick = { valid?.let(onJoin) }, enabled = valid != null, modifier = Modifier.fillMaxWidth()) { Text("Join the game") }
    }
}

/** The name a player gives for an online game, shown to the other player. */
@Composable
fun NameField(name: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = name,
        // Longer names are cut off as they're typed, rather than refused later.
        onValueChange = { text -> if (text.codePointCount(0, text.length) <= Ticket.MAX_NAME_LENGTH) onChange(text) },
        label = { Text("Your name") },
        supportingText = { Text("Shown to your friend in this game") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Creating or joining a game: a spinner, or what went wrong. */
@Composable
fun ConnectingScreen(connecting: Connecting, onRetry: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    OnlinePage(onBack, modifier) {
        Text("Play a friend", style = MaterialTheme.typography.titleLarge)
        if (connecting.error == null) {
            Waiting(connecting.title)
        } else {
            Text(connecting.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryPill(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
                PrimaryPill(onClick = onRetry, modifier = Modifier.weight(1f)) { Text("Try again") }
            }
        }
    }
}

/** E.g. "You play White · Standard · up to 4 timelines each". */
fun ticketSummary(ticket: Ticket): String {
    val limit = ticket.timelineLimit?.let { "up to $it timelines each" } ?: "unlimited timelines"
    return "You play ${ticket.side.displayName} · ${ticket.variant.displayName} · $limit"
}

@Composable
private fun Waiting(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Palette.accent)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun OnlinePage(onBack: () -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().background(Palette.background)) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth()) { RoundIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) }
            Spacer(Modifier.height(32.dp))
            SlabCard(Modifier.widthIn(max = 440.dp).fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
            }
        }
    }
}

private fun share(context: Context, link: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, "Play Multiverse Chess with me: $link")
    context.startActivity(Intent.createChooser(send, "Invite a friend"))
}

private fun copy(context: Context, link: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Invitation", link))
}
