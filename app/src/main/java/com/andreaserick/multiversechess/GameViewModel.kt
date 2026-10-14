package com.andreaserick.multiversechess

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.andreaserick.multiversechess.ai.AiException
import com.andreaserick.multiversechess.ai.AiGames
import com.andreaserick.multiversechess.ai.AiSettings
import com.andreaserick.multiversechess.ai.AiStore
import com.andreaserick.multiversechess.ai.ChatMessage
import com.andreaserick.multiversechess.ai.DeviceModel
import com.andreaserick.multiversechess.ai.DeviceModels
import com.andreaserick.multiversechess.ai.GeminiModel
import com.andreaserick.multiversechess.ai.LanguageModel
import com.andreaserick.multiversechess.ai.OnDeviceModel
import com.andreaserick.multiversechess.ai.ServerModel
import com.andreaserick.multiversechess.engine.ComputerPlayer
import com.andreaserick.multiversechess.engine.GameMode
import com.andreaserick.multiversechess.engine.GameSession
import com.andreaserick.multiversechess.engine.OnlineEvent
import com.andreaserick.multiversechess.engine.Outcome
import com.andreaserick.multiversechess.engine.Pos
import com.andreaserick.multiversechess.engine.Puzzle
import com.andreaserick.multiversechess.engine.Puzzles
import com.andreaserick.multiversechess.engine.PuzzleCodec
import com.andreaserick.multiversechess.engine.PuzzleGenerator
import com.andreaserick.multiversechess.engine.SavedGame
import com.andreaserick.multiversechess.engine.Side
import com.andreaserick.multiversechess.engine.Variant
import com.andreaserick.multiversechess.online.Connecting
import com.andreaserick.multiversechess.online.Invitation
import com.andreaserick.multiversechess.online.OnlineStatus
import com.andreaserick.multiversechess.online.RelayClient
import com.andreaserick.multiversechess.online.RelayConnection
import com.andreaserick.multiversechess.online.RelayException
import com.andreaserick.multiversechess.online.RelayMessage
import com.andreaserick.multiversechess.online.Ticket
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GameViewModel(application: Application) : AndroidViewModel(application) {
    /** The current game, or null while a menu is showing. */
    var session by mutableStateOf<GameSession?>(null)
        private set

    /** Whether the puzzle list is showing (when there is no game). */
    var showingPuzzles by mutableStateOf(false)
        private set

    /** Busy computing: the computer's turn, or checking a puzzle answer. */
    var thinking by mutableStateOf(false)
        private set

    private val prefs = application.getSharedPreferences("progress", Context.MODE_PRIVATE)

    /** Ids of puzzles solved without looking at the solution. */
    var solvedPuzzles by mutableStateOf(prefs.getStringSet(KEY_SOLVED, emptySet())!!.toSet())
        private set

    private var job: Job? = null

    /** Where the current game is kept, so it survives the app being closed. */
    private val saveFile = File(application.filesDir, "game.txt")

    /** Saves run one at a time, in order, off the main thread. */
    private val saving = Dispatchers.IO.limitedParallelism(1)

    /** An unfinished game that can be continued from the menu. */
    var resumable by mutableStateOf<GameSession?>(null)
        private set

    init {
        viewModelScope.launch {
            val saved = withContext(saving) {
                runCatching { saveFile.takeIf { it.exists() }?.readText()?.let(SavedGame::decode) }.getOrNull()
            }
            if (session == null && saved != null && saved.outcome == Outcome.Ongoing) resumable = saved
        }
    }

    /** Puzzles made by [generatePuzzle], kept on the device. */
    var generatedPuzzles by mutableStateOf<List<Puzzle>>(emptyList())
        private set

    /** The built-in puzzles followed by the generated ones. */
    val allPuzzles: List<Puzzle> get() = Puzzles.all + generatedPuzzles

    /** New puzzles can be generated once every built-in one is solved. */
    val canGenerate: Boolean get() = Puzzles.all.all { it.id in solvedPuzzles }

    /** Busy making a new puzzle. */
    var generating by mutableStateOf(false)
        private set

    /** Set when generating gave up without a puzzle. */
    var generateFailed by mutableStateOf(false)
        private set

    private val puzzleFile = File(application.filesDir, "puzzles.txt")
    private var generateJob: Job? = null

    init {
        viewModelScope.launch {
            val loaded = withContext(saving) {
                runCatching { puzzleFile.takeIf { it.exists() }?.readText()?.let(PuzzleCodec::decodeAll) }.getOrNull()
            }
            if (loaded != null) generatedPuzzles = loaded
        }
    }

    /** Makes a new puzzle in the background, keeps it, and opens it. */
    fun generatePuzzle() {
        if (generating || !canGenerate) return
        generating = true
        generateFailed = false
        generateJob = viewModelScope.launch {
            val number = generatedPuzzles.size + 1
            val puzzle = withContext(Dispatchers.Default) {
                val job = coroutineContext.job
                PuzzleGenerator(stop = { !job.isActive }).generate(number)
            }
            generating = false
            if (puzzle == null) {
                generateFailed = true
                return@launch
            }
            generatedPuzzles = generatedPuzzles + puzzle
            val all = generatedPuzzles
            launch(saving) { runCatching { puzzleFile.writeText(PuzzleCodec.encodeAll(all)) } }
            startPuzzle(puzzle)
        }
    }

    fun startGame(mode: GameMode) {
        aiRemark = null
        show(GameSession(mode))
    }

    fun continueGame() {
        resumable?.let { show(it) }
    }

    fun openPuzzles() {
        showingPuzzles = true
    }

    fun startPuzzle(puzzle: Puzzle) = startGame(GameMode(puzzle = puzzle))

    /** The puzzle after the current one, if any. */
    val nextPuzzle: Puzzle?
        get() {
            val current = session?.mode?.puzzle ?: return null
            val all = allPuzzles
            return all.getOrNull(all.indexOfFirst { it.id == current.id } + 1)
        }

    /**
     * Leaves the game, back to the puzzle list if it was a puzzle; from the puzzle list, to the
     * menu. Stops creating or joining an online game.
     */
    fun back() {
        stopJob()
        if (showingAiSettings) {
            showingAiSettings = false
            return
        }
        if (connecting != null) {
            connectJob?.cancel()
            connecting = null
            return
        }
        if (invitation != null) {
            invitation = null
            return
        }
        if (session == null) {
            showingPuzzles = false
            generateJob?.cancel()
            generating = false
            designJob?.cancel()
            designing = null
        }
        leaveGame()
    }

    /** Closes the game; an unfinished one stays on offer in the menu. */
    private fun leaveGame() {
        stopJob()
        val current = session
        if (current != null && current.mode.puzzle == null && current.mode.onlineSide == null && current.outcome == Outcome.Ongoing) {
            resumable = current
        }
        leaveOnline()
        session = null
        aiRemark = null
    }

    fun tap(pos: Pos) = update { it.tap(pos) }

    /** Checking whether the opponent has a legal reply (or a puzzle answer is mate) can take a moment. */
    fun submit() {
        val current = session?.takeIf { outbox == null } ?: return
        runInBackground(current) { session, _ -> session.submit() }
    }

    /** Searches every turn; this can take a while, and Undo or leaving the game cancels it. */
    fun claimNoLegalTurn() {
        val current = session?.takeIf { outbox == null } ?: return
        runInBackground(current) { session, stop -> session.claimNoLegalTurn(stop) }
    }

    fun showSolution() = update { it.showSolution() }

    fun restartPuzzle() {
        stopJob()
        update { it.restartPuzzle() }
    }

    fun resign() = update { it.resign() }

    fun undo() {
        stopJob()
        update { it.undo() }
    }

    private inline fun update(action: (GameSession) -> GameSession) {
        if (outbox != null) return
        session?.let { advance(it, action(it)) }
    }

    /**
     * Shows [next], reached from [from]. Online, a submitted turn or an ended game goes to the
     * relay instead, and shows once it comes back, so both players see the same game.
     */
    private fun advance(from: GameSession, next: GameSession) {
        val event = when {
            from.mode.onlineSide == null -> null
            next.state.sideToMove != from.state.sideToMove ->
                OnlineEvent.Turn(from.state.turnMoves.map { it.from to it.to }, next.outcome, next.undecided)
            from.outcome == Outcome.Ongoing && next.outcome != Outcome.Ongoing -> OnlineEvent.End(next.outcome)
            else -> null
        }
        if (event == null) show(next) else send(event)
    }

    private fun show(next: GameSession) {
        val previous = session
        session = next
        recordSolved(previous, next)
        save(next)
        if (next.isComputerTurn) {
            val model = if (next.mode.aiOpponent) aiModel else null
            if (model != null) {
                runAiTurn(next, model)
            } else {
                runInBackground(next) { session, stop -> session.applyComputerTurn(ComputerPlayer().chooseTurn(session.state, stop)) }
            }
        }
    }

    /** The AI chooses the computer's turn; it can take a while, and Undo or leaving the game abandons it. */
    private fun runAiTurn(from: GameSession, model: LanguageModel) {
        stopJob()
        thinking = true
        job = viewModelScope.launch {
            val turn = AiGames.turn(model, from.state)
            val next = withContext(Dispatchers.Default) { from.applyComputerTurn(turn.moves) }
            if (session === from) {
                thinking = false
                aiRemark = turn.remark?.let { "${model.name}: \u201c$it\u201d" }
                show(if (turn.fallback != null) next.copy(message = turn.fallback) else next)
            }
        }
    }

    /** Keeps [game] on disk while it's unfinished; a finished game, a puzzle or an online game isn't kept. */
    private fun save(game: GameSession) {
        if (game.mode.puzzle != null || game.mode.onlineSide != null) return
        resumable = null
        viewModelScope.launch(saving) {
            runCatching {
                if (game.outcome == Outcome.Ongoing) saveFile.writeText(SavedGame.encode(game)) else saveFile.delete()
            }
        }
    }

    private fun recordSolved(previous: GameSession?, next: GameSession) {
        val puzzle = next.mode.puzzle ?: return
        if (next.outcome != Outcome.Checkmate(next.state.sideToMove.opponent) || previous?.outcome != Outcome.Ongoing) return
        if (next.solutionShown || puzzle.id in solvedPuzzles) return
        solvedPuzzles = solvedPuzzles + puzzle.id
        prefs.edit { putStringSet(KEY_SOLVED, solvedPuzzles) }
    }

    /** Runs [action] off the main thread; its stop callback turns true once the job is cancelled. */
    private fun runInBackground(from: GameSession, action: (GameSession, () -> Boolean) -> GameSession) {
        stopJob()
        thinking = true
        job = viewModelScope.launch {
            val next = withContext(Dispatchers.Default) {
                val job = coroutineContext.job
                action(from) { !job.isActive }
            }
            // Ignore the result if the player undid or left the game meanwhile.
            if (session === from) {
                thinking = false
                advance(from, next)
            }
        }
    }

    private fun stopJob() {
        job?.cancel()
        job = null
        thinking = false
    }

    // AI: a language model to play against, and to design puzzles.

    private val aiStore = AiStore(application)

    /** The player's AI choice. */
    var aiSettings by mutableStateOf(aiStore.load())
        private set

    /** The language model to use, if one is set up. */
    val aiModel: LanguageModel? get() = aiStore.model(aiSettings)

    /** What the AI said with its last turn, shown under the status. */
    var aiRemark by mutableStateOf<String?>(null)
        private set

    var showingAiSettings by mutableStateOf(false)
        private set

    fun openAiSettings() {
        showingAiSettings = true
    }

    fun saveAiSettings(settings: AiSettings) {
        if (settings.deviceModel != aiSettings.deviceModel || settings.provider != aiSettings.provider) {
            viewModelScope.launch { OnDeviceModel.release() }
        }
        aiSettings = settings
        aiStore.save(settings)
    }

    /** The answer to a short test message, or what went wrong; null while waiting. */
    var aiTest by mutableStateOf<Result<String>?>(null)
        private set
    private var aiTestJob: Job? = null

    /** Sends [settings]' model a short message, to check it works. */
    fun testAi(settings: AiSettings) {
        val model = aiStore.model(settings) ?: return
        aiTestJob?.cancel()
        aiTest = null
        aiTestJob = viewModelScope.launch {
            aiTest = try {
                Result.success(model.chat("You are a friendly chess player. Answer in one short sentence.", listOf(ChatMessage(false, "Say hello to your opponent."))))
            } catch (e: AiException) {
                Result.failure(e)
            }
        }
    }

    fun clearAiTest() {
        aiTestJob?.cancel()
        aiTest = null
    }

    /** Models offered by the Gemini API or a server, for the settings; or why they couldn't be listed. */
    var aiModels by mutableStateOf<Result<List<String>>?>(null)
        private set

    fun listAiModels(settings: AiSettings) {
        aiModels = null
        viewModelScope.launch {
            aiModels = try {
                Result.success(
                    when (settings.provider) {
                        com.andreaserick.multiversechess.ai.AiProvider.GEMINI -> GeminiModel.models(settings.geminiKey)
                        else -> ServerModel.models(settings.serverUrl, settings.serverKey.ifBlank { null })
                    },
                )
            } catch (e: AiException) {
                Result.failure(e)
            }
        }
    }

    /** On-device models downloaded (by file name). */
    var installedModels by mutableStateOf(installed())
        private set

    private fun installed(): Set<String> = aiStore.modelsDir.listFiles { file -> file.name.endsWith(".litertlm") }?.map { it.name }?.toSet().orEmpty()

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
        downloadJobs[model.file] = viewModelScope.launch {
            try {
                DeviceModels.download(model, aiStore.modelsDir) { done ->
                    viewModelScope.launch { if (model.file in downloads) downloads = downloads + (model.file to done) }
                }
                installedModels = installed()
                // A first model downloaded is the one to use.
                if (aiSettings.deviceModel.isBlank()) saveAiSettings(aiSettings.copy(deviceModel = model.file))
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

    fun deleteModel(model: DeviceModel) {
        viewModelScope.launch {
            OnDeviceModel.release()
            withContext(Dispatchers.IO) {
                File(aiStore.modelsDir, model.file).delete()
                File(aiStore.modelsDir, model.file + ".part").delete()
            }
            installedModels = installed()
            if (aiSettings.deviceModel == model.file) saveAiSettings(aiSettings.copy(deviceModel = installedModels.firstOrNull().orEmpty()))
        }
    }

    /** What designing an AI puzzle is doing now; null when not designing. */
    var designing by mutableStateOf<String?>(null)
        private set

    /** Why the last AI puzzle couldn't be made. */
    var designFailed by mutableStateOf<String?>(null)
        private set
    private var designJob: Job? = null

    /** Has the AI design a puzzle, optionally on a [theme]; it's kept with the generated ones, and opened. */
    fun designPuzzle(theme: String?) {
        val model = aiModel ?: return
        if (designing != null) return
        designFailed = null
        designing = "${model.name} is designing a puzzle…"
        designJob = viewModelScope.launch {
            try {
                val puzzle = AiGames.designPuzzle(model, generatedPuzzles.size + 1, theme) { designing = it }
                generatedPuzzles = generatedPuzzles + puzzle
                val all = generatedPuzzles
                launch(saving) { runCatching { puzzleFile.writeText(PuzzleCodec.encodeAll(all)) } }
                designing = null
                startPuzzle(puzzle)
            } catch (e: AiException) {
                designFailed = e.message
            } finally {
                designing = null
            }
        }
    }

    // Online games. Each is a list of events kept by the relay (see relay/); both players' apps
    // play every event in the relay's order, their own turns included, once the relay echoes them.

    private val relay = RelayClient(BuildConfig.RELAY_URL)

    /** The seat in an unfinished online game. Kept apart from progress, so backups leave it out (see backup_rules.xml). */
    private val onlinePrefs = application.getSharedPreferences("online", Context.MODE_PRIVATE)

    /** Whether this build knows a relay to play online through. */
    val canPlayOnline: Boolean get() = relay.isConfigured

    /** The online game being played, or waited for in the lobby; null otherwise. */
    var online by mutableStateOf<OnlineStatus?>(null)
        private set

    /** Creating or joining an online game, before it opens. */
    var connecting by mutableStateOf<Connecting?>(null)
        private set

    /** An unfinished online game that can be rejoined from the menu. */
    var resumableOnline by mutableStateOf(onlinePrefs.getString(KEY_ONLINE, null)?.let(Ticket::decode))
        private set

    private var connectJob: Job? = null
    private var retry: (() -> Unit)? = null
    private var onlineJob: Job? = null
    private var connection: RelayConnection? = null

    /** How many of the online game's events [session] has played. */
    private var applied = 0

    /** This player's event waiting for the relay's receipt: its index and text. */
    private var outbox: Pair<Int, String>? = null

    /** A game this player was invited to, waiting for them to give their name and join. */
    var invitation by mutableStateOf<Invitation?>(null)
        private set

    /** The name this player gave last time, offered again. */
    var playerName by mutableStateOf(prefs.getString(KEY_NAME, null).orEmpty())
        private set

    /** Opens a game online, with this player, called [name], on [side], and a link to invite a friend to it. */
    fun inviteFriend(variant: Variant, timelineLimit: Int?, side: Side, name: String) {
        rememberName(name)
        openOnline("Creating the game…") { startOnline(relay.create(variant, timelineLimit, side, name)) }
    }

    /**
     * Looks up the game [code], from an invitation link or typed in, to show who invites this
     * player before they give their name ([acceptInvitation]). A game this player is already in
     * just opens.
     */
    fun joinFriend(code: String) {
        val wanted = Ticket.parseCode(code) ?: return
        if (online?.ticket?.code == wanted) return
        val saved = resumableOnline
        if (saved?.code == wanted) {
            connectJob?.cancel()
            connecting = null
            return startOnline(saved)
        }
        openOnline("Looking up game ${Ticket.displayCode(wanted)}…") {
            val found = relay.invitation(wanted)
            if (found.full) throw RelayException("Full", 409)
            invitation = found
        }
    }

    /** Joins the game of the [invitation] as [name]. */
    fun acceptInvitation(name: String) {
        val accepted = invitation ?: return
        invitation = null
        rememberName(name)
        openOnline("Joining ${accepted.hostName}'s game…") { startOnline(relay.join(accepted.code, name)) }
    }

    private fun rememberName(name: String) {
        playerName = name
        prefs.edit { putString(KEY_NAME, name) }
    }

    fun continueOnline() {
        resumableOnline?.let(::startOnline)
    }

    fun retryConnecting() {
        retry?.invoke()
    }

    /** Runs [action], a request to the relay, showing [title] meanwhile, and what went wrong if it fails. */
    private fun openOnline(title: String, action: suspend () -> Unit) {
        connectJob?.cancel()
        retry = { openOnline(title, action) }
        connecting = Connecting(title)
        connectJob = viewModelScope.launch {
            try {
                action()
            } catch (e: IOException) {
                Log.w(TAG, "Couldn't open the online game", e)
                connecting = Connecting(title, describe(e))
                return@launch
            }
            connecting = null
        }
    }

    private fun describe(e: IOException): String = when ((e as? RelayException)?.status) {
        404 -> "There's no game with this code. Check it, or ask for a new invitation: games are forgotten after a month."
        409 -> "This game already has two players."
        null -> "Couldn't reach the server. Check your internet connection."
        else -> "The server refused: ${e.message}"
    }

    /** Enters the online game [ticket] has a seat in, and stays connected while it's open. */
    private fun startOnline(ticket: Ticket) {
        leaveGame()
        showingPuzzles = false
        invitation = null
        resumableOnline = ticket
        onlinePrefs.edit { putString(KEY_ONLINE, ticket.encode()) }
        online = OnlineStatus(ticket, relay.inviteLink(ticket.code))
        session = GameSession(ticket.mode)
        applied = 0
        outbox = null
        onlineJob = viewModelScope.launch {
            var wait = RECONNECT_MIN_MILLIS
            while (true) {
                try {
                    relay.connect(ticket).collect { message ->
                        wait = RECONNECT_MIN_MILLIS
                        receive(message)
                    }
                } catch (e: RelayException) {
                    if (e.isFinal) {
                        stopOnline("This game no longer exists.")
                        return@launch
                    }
                } catch (_: IOException) {
                }
                connection = null
                online = online?.copy(connected = false)
                delay(wait)
                wait = (wait * 2).coerceAtMost(RECONNECT_MAX_MILLIS)
            }
        }
    }

    private suspend fun receive(message: RelayMessage) {
        when (message) {
            is RelayMessage.Hello -> {
                connection = message.connection
                online = online?.copy(connected = true, opponentJoined = message.opponentJoined, opponentPresent = message.opponentPresent)
                learnNames(message.names)
                for (index in applied until message.events.size) {
                    val (sender, event) = message.events[index]
                    if (!play(sender, event)) return
                }
                // A turn sent before the connection dropped: send it again, unless it got there.
                outbox?.let { (index, event) -> if (index == applied) message.connection.send(index, event) else outbox = null }
            }
            is RelayMessage.Event -> when {
                message.index == applied -> play(message.sender, message.event)
                // Events were missed: connecting again brings them all.
                message.index > applied -> connection?.close()
            }
            is RelayMessage.Joined -> {
                online = online?.copy(opponentJoined = true)
                online?.let { learnNames(mapOf(it.ticket.side.opponent to message.name)) }
            }
            is RelayMessage.Presence -> online = online?.copy(opponentPresent = message.opponentPresent)
        }
        online = online?.copy(sending = outbox != null)
    }

    /** Shows the players' [names], and keeps the friend's for the menu's Continue button. */
    private fun learnNames(names: Map<Side, String>) {
        val status = online ?: return
        online = status.copy(names = status.names + names)
        val opponent = names[status.ticket.side.opponent] ?: return
        val saved = resumableOnline?.takeIf { it.code == status.ticket.code && it.opponentName != opponent } ?: return
        val updated = saved.copy(opponentName = opponent)
        resumableOnline = updated
        onlinePrefs.edit { putString(KEY_ONLINE, updated.encode()) }
    }

    /**
     * Plays the online game's next event, sent by [sender]'s player; false if it breaks the rules,
     * which stops the game. A checkmate or stalemate the friend's app claims is searched for here
     * before it counts.
     */
    private suspend fun play(sender: Side, event: String): Boolean {
        val current = session ?: return false
        val next = OnlineEvent.decode(event)?.let { current.applyEvent(it, sender) }
        if (next == null) {
            stopOnline("Your friend's app sent a move that isn't allowed, so the game was stopped. Make sure you both have the latest version.")
            return false
        }
        val claimed = next.outcome is Outcome.Checkmate || next.outcome == Outcome.Stalemate
        if (claimed && current.outcome == Outcome.Ongoing && sender != current.mode.onlineSide) {
            thinking = true
            val holds = withContext(Dispatchers.Default) {
                val job = coroutineContext.job
                next.confirmsOutcome { !job.isActive }
            }
            thinking = false
            if (!holds) {
                stopOnline("Your friend's app said the game ended in ${if (next.outcome == Outcome.Stalemate) "stalemate" else "checkmate"}, but it didn't, so the game was stopped.")
                return false
            }
        }
        if (outbox?.first == applied) outbox = null
        applied++
        // A search still running (No legal turn?) is about a position that's gone.
        stopJob()
        session = next
        if (next.outcome != Outcome.Ongoing) forgetOnline()
        return true
    }

    /** Offers [event] to the relay; it's played once the relay echoes it back. */
    private fun send(event: OnlineEvent) {
        val text = OnlineEvent.encode(event)
        outbox = applied to text
        connection?.send(applied, text)
        online = online?.copy(sending = true)
    }

    /** The online game can't go on: says why, and stops connecting. */
    private fun stopOnline(problem: String) {
        onlineJob?.cancel()
        connection?.close()
        connection = null
        outbox = null
        online = online?.copy(connected = false, sending = false, problem = problem)
        forgetOnline()
    }

    private fun forgetOnline() {
        resumableOnline = null
        onlinePrefs.edit { remove(KEY_ONLINE) }
    }

    /** Disconnects from the online game, if any; an unfinished one can be rejoined from the menu. */
    private fun leaveOnline() {
        onlineJob?.cancel()
        onlineJob = null
        connection?.close()
        connection = null
        outbox = null
        online = null
    }

    private companion object {
        const val TAG = "MultiverseChess"
        const val KEY_SOLVED = "solved_puzzles"
        const val KEY_ONLINE = "game"
        const val KEY_NAME = "player_name"
        const val RECONNECT_MIN_MILLIS = 1_000L
        const val RECONNECT_MAX_MILLIS = 15_000L
    }
}
