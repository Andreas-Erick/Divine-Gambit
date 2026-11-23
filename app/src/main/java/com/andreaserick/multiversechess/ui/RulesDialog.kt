package com.andreaserick.multiversechess.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun RulesDialog(onDismiss: () -> Unit, onShowTipsAgain: (() -> Unit)? = null) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
        title = { Text("How to play") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                for ((heading, body) in RULES) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(heading, style = MaterialTheme.typography.titleSmall, color = Palette.accent)
                        Text(body, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text("Reading the boards", style = MaterialTheme.typography.titleSmall, color = Palette.accent)
                BoardLegend()
                Text("Buttons", style = MaterialTheme.typography.titleSmall, color = Palette.accent)
                for (button in BUTTONS) ButtonHelpRow(button)
                onShowTipsAgain?.let { show ->
                    TextButton(onClick = {
                        show()
                        onDismiss()
                    }) { Text("Show beginner tips again") }
                }
            }
        },
    )
}

/** One button explained in How to play: an [icon], or a pill reading [pill]. */
private class ButtonHelp(val name: String, val text: String, val icon: ImageVector? = null, val pill: String? = null)

private val BUTTONS = listOf(
    ButtonHelp(
        "Back",
        "Leave the game; it's saved, and Continue game in the menu picks it up again. The × beside Continue game throws it away.",
        icon = Icons.AutoMirrored.Filled.ArrowBack,
    ),
    ButtonHelp("How to play", "Opens these rules.", icon = Icons.Filled.Info),
    ButtonHelp(
        "More",
        "In a game: the move list, Stuck? Check for a legal turn (searches every turn when you think you're stuck) and " +
            "Resign. In a puzzle: a hint, restarting it, and showing the solution.",
        icon = Icons.Filled.MoreVert,
    ),
    ButtonHelp("Restart puzzle", "Puts the puzzle back to its start. Undo takes you back to where you were.", icon = Icons.Filled.Refresh),
    ButtonHelp("Overview", "Zooms out to show the whole multiverse.", icon = OverviewIcon),
    ButtonHelp("History view", "Shows only the selected piece's timeline through time, without your opponent's boards.", icon = HistoryIcon),
    ButtonHelp("Parallel view", "Shows only the selected piece's moment, across every timeline.", icon = ParallelIcon),
    ButtonHelp("Undo", "Takes back your last move. Against the computer, it also takes back its reply.", pill = "Undo"),
    ButtonHelp("Present", "Brings you back to the boards you have to play.", pill = "Present"),
    ButtonHelp("End turn", "Ends your turn once every board in the present has been played.", pill = "End turn"),
)

@Composable
private fun ButtonHelpRow(button: ButtonHelp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(72.dp), contentAlignment = Alignment.Center) {
            if (button.icon != null) {
                Box(
                    Modifier
                        .size(36.dp)
                        .background(Palette.card, CircleShape)
                        .border(1.dp, Palette.presentEdge, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(button.icon, contentDescription = null, tint = Palette.ink, modifier = Modifier.size(20.dp))
                }
            } else {
                Text(
                    button.pill.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .border(1.dp, Palette.ink.copy(alpha = 0.7f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(button.name, style = MaterialTheme.typography.labelLarge)
            Text(button.text, style = MaterialTheme.typography.bodySmall, color = Palette.inkSoft)
        }
    }
}

private val RULES = listOf(
    "The short version" to
        "It's chess, but pieces can also move to earlier boards, and that starts a new timeline: a " +
        "new row of boards. Tap one of your pieces, tap a marked square, and once every board with " +
        "a gold ring has a move, tap End turn. Checkmate a king on any board to win. Hold a round " +
        "button to see what it does. The rest below is for when you want it.",
    "The multiverse" to
        "Each row is a timeline (teal ribbon) and each column a half-move, advancing to the right. " +
        "Drag to pan and pinch to zoom.",
    "Finding your way" to
        "Tap a timeline's ribbon, or its label on the left edge, to see the whole timeline. Tap a " +
        "board that's too small to play on to zoom in on it. The Overview button (four corners) shows the whole " +
        "multiverse, and Present brings you back to the boards you have to play. History view " +
        "keeps only the selected piece's timeline, through time; Parallel view only its moment, " +
        "across every timeline. Both hide your opponent's boards.",
    "Four dimensions" to
        "Pieces move as in chess, but through file, rank, time (one step = one full turn back) and " +
        "timeline (one row up or down).",
    "Pieces" to
        "Rook: any distance along one dimension. Bishop: equal distance along two. Queen: equal " +
        "distance along any number. King: one step in any combination. Knight: two along one " +
        "dimension and one along another.",
    "Pawns" to
        "Pawns advance one rank, or one timeline toward the opponent (up for White, down for Black); " +
        "an unmoved pawn may go two either way. They capture diagonally on the board, or one " +
        "timeline forward and one turn back or ahead.",
    "Branching" to
        "Moving to an earlier board creates a new timeline. Moving to the latest board of another " +
        "timeline just continues it.",
    "The present" to
        "The pale column marks the present: the earliest end of any active timeline. You must move " +
        "on every board in it (gold ring), may move on your other raised boards, then tap End turn.",
    "Timeline limit" to
        "Unless you choose Unlimited when starting a game, each player may only create a few " +
        "timelines. After that they can't branch any more: moves into the past are no longer " +
        "possible, so they can't attack a king's past either.",
    "Active timelines" to
        "Each player may have only one more active timeline than the opponent. Extra timelines are " +
        "faded and don't hold back the present. A teal arrow at the end of the present means " +
        "your next new timeline would be active, so branching into the past would pull the " +
        "present back with it.",
    "Check and mate" to
        "You can't submit a turn that lets the opponent capture any of your kings (or other royal " +
        "pieces), now or in the past. A red ! marks a timeline whose advancement would let a king be captured. With no " +
        "way to move the present without leaving a king attacked you are checkmated, or " +
        "stalemated if no king is threatened. After the game you can still try the loser's " +
        "moves to see why.",
    "AI opponent" to
        "In AI opponent (below the menu) you can connect a language model: Gemini with your API key, " +
        "a model on your computer (LM Studio or Ollama), or one downloaded to this phone. Choose it " +
        "as your Opponent under Singleplayer to play it: it picks one of the legal turns the engine finds and says " +
        "something about it. Under Puzzles it can also design puzzles; the engine checks each one " +
        "really is mate in one turn, and makes one itself if the AI's don't work out.",
    "Playing a friend" to
        "Invite a friend gives you a link to send. When your friend opens it on their phone and " +
        "gives their name, the game begins; without the app, the link shows a code they can type " +
        "under Join a friend. The names you both give are shown to each other in that game. " +
        "Undo only takes back moves you haven't submitted. If either of you leaves, the game " +
        "waits: Continue game with a friend picks it up again.",
    "The Inferno" to
        "If you start a game with the Inferno switched on, a piece that's taken goes to the Inferno. " +
        "Tap Descend to go after it (with several taken, a scroll asks which one you seek): there you " +
        "solve trials, each a puzzle to checkmate in one turn, while the game goes on. A pawn needs one " +
        "trial, a knight or bishop two, a rook three and a queen four; tap your pieces at the top of " +
        "the Inferno to switch between them. A piece that has passed its trials returns at the start of your turn, on a square " +
        "where it can't be taken straight away. While it's your turn on the board you have 90 seconds " +
        "there per turn; while your opponent thinks, the clock stops. Online, you each have your own " +
        "Inferno, and a piece that returns appears on both phones. If the game ends while you're " +
        "in the Inferno, you'll be told.",
    "Fairy pieces" to
        "Some variants add pieces drawn as lettered discs. S, princess: a rook or bishop move. " +
        "U, unicorn: equal distance along exactly three dimensions. D, dragon: equal distance " +
        "along all four. W, brawn: a pawn that can capture one step along any diagonal that " +
        "includes a forward step (rank or timeline) and no backward one. Y, royal queen: moves " +
        "like a queen but is royal, so it must be protected like a king. C, common king: moves " +
        "like a king but isn't royal, so it may be attacked or lost.",
)
