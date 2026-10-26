package com.risaina.app.feature.messages.games

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.risaina.app.core.data.GameKinds
import com.risaina.app.core.data.GameStartOptions
import com.risaina.app.core.designsystem.components.GhostButton
import com.risaina.app.core.designsystem.components.PrimaryButton
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.network.GameCatalogDto
import com.risaina.app.core.network.GameDto
import com.risaina.app.core.network.GameKindDto

/**
 * Pick a game and its options. One game can be open at a time, so with one going the sheet says so
 * up front, and starting another first asks to end it.
 */
@Composable
fun GamePickerSheet(
    catalog: GameCatalogDto?,
    openGame: GameDto?,
    myId: String?,
    partner: String,
    startingKind: String?,
    onStart: (GameStartOptions) -> Unit,
    onEndAndStart: (String, GameStartOptions) -> Unit,
    onEndOpen: (String) -> Unit,
    onGoTo: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // The last choices are kept for the session, so "another round, same settings" is one tap.
    var kind by rememberSaveable { mutableStateOf(GameKinds.TICTACTOE) }
    var withPrize by rememberSaveable { mutableStateOf(true) }
    var category by rememberSaveable { mutableIntStateOf(-1) } // -1 = any
    var difficulty by rememberSaveable { mutableStateOf("") } // "" = any
    var deck by rememberSaveable { mutableStateOf("mix") }
    var confirm by remember { mutableStateOf<GameStartOptions?>(null) }
    var confirmEndOnly by remember { mutableStateOf(false) }

    val kinds = catalog?.kinds.orEmpty()
    val selected = kinds.firstOrNull { it.kind == kind } ?: kinds.firstOrNull()
    val legacy = catalog?.legacyServer == true
    fun options(k: GameKindDto) = GameStartOptions(
        kind = k.kind,
        justForFun = !withPrize && k.competitive && !legacy,
        category = category.takeIf { it >= 0 },
        difficulty = difficulty.ifEmpty { null },
        deck = deck.takeIf { it != "mix" },
    )

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Text("Play a game", style = MaterialTheme.typography.headlineSmall)
            Muted("Pick one and $partner gets a nudge to join.")
            Spacer(Modifier.height(12.dp))

            openGame?.let { open ->
                Column(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.secondaryContainer).padding(14.dp),
                ) {
                    Text("You have ${gamePhrase(open.kind)} going", style = MaterialTheme.typography.titleSmall)
                    Text(if (isMyTurn(open, myId)) "It's your turn" else "Waiting for $partner", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        GhostButton("Go to it", onClick = { onGoTo(open.id) })
                        GhostButton("End it", onClick = { confirmEndOnly = true })
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            if (catalog == null) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(24.dp))
            } else {
                kinds.forEach { k ->
                    KindTile(k, selected?.kind == k.kind) { kind = k.kind }
                    if (selected?.kind == k.kind && !legacy) {
                        KindOptions(
                            k, catalog, withPrize = withPrize, onPrize = { withPrize = it },
                            category = category, onCategory = { category = it },
                            difficulty = difficulty, onDifficulty = { difficulty = it },
                            deck = deck, onDeck = { deck = it },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(6.dp))
                selected?.let { k ->
                    val starting = startingKind != null
                    PrimaryButton(
                        text = when {
                            starting && startingKind == GameKinds.TRIVIA -> "Getting questions…"
                            starting -> "Starting…"
                            else -> "Let's play"
                        },
                        onClick = { if (openGame != null) confirm = options(k) else onStart(options(k)) },
                        loading = starting,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    confirm?.let { opts ->
        val open = openGame
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("End the current game?") },
            text = { Text("This ends ${open?.let { gamePhrase(it.kind) } ?: "the game"} for both of you and starts ${gamePhrase(opts.kind)}.") },
            confirmButton = { TextButton(onClick = { confirm = null; if (open != null) onEndAndStart(open.id, opts) else onStart(opts) }) { Text("End and start") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Keep playing") } },
        )
    }
    if (confirmEndOnly && openGame != null) {
        AlertDialog(
            onDismissRequest = { confirmEndOnly = false },
            title = { Text("End this game?") },
            text = { Text("${gameTitle(openGame.kind)} ends for both of you.") },
            confirmButton = { TextButton(onClick = { confirmEndOnly = false; onEndOpen(openGame.id) }) { Text("End it") } },
            dismissButton = { TextButton(onClick = { confirmEndOnly = false }) { Text("Keep playing") } },
        )
    }
}

@Composable
private fun KindTile(k: GameKindDto, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
            .background(if (selected) colors.primaryContainer else colors.surfaceContainerHigh)
            .border(1.dp, if (selected) colors.primary else Color.Transparent, MaterialTheme.shapes.large)
            .clickable(role = Role.RadioButton, onClick = onClick).semantics { this.selected = selected }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(k.emoji, fontSize = 28.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(k.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(k.blurb, style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
        }
    }
}

@Composable
private fun KindOptions(
    k: GameKindDto,
    catalog: GameCatalogDto,
    withPrize: Boolean, onPrize: (Boolean) -> Unit,
    category: Int, onCategory: (Int) -> Unit,
    difficulty: String, onDifficulty: (String) -> Unit,
    deck: String, onDeck: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 6.dp, top = 8.dp)) {
        when (k.kind) {
            GameKinds.TRIVIA -> {
                Text("Category", style = MaterialTheme.typography.labelLarge)
                ChipRow(listOf(-1 to "Any") + catalog.trivia.categories.mapNotNull { c -> c.id?.let { it to c.name } }, category, onCategory)
                Spacer(Modifier.height(6.dp))
                Text("Difficulty", style = MaterialTheme.typography.labelLarge)
                ChipRow(listOf("" to "Any") + catalog.trivia.difficulties.map { it to it.replaceFirstChar { c -> c.uppercase() } }, difficulty, onDifficulty)
                Spacer(Modifier.height(4.dp))
                Muted(catalog.trivia.attribution.ifBlank { "Questions from Open Trivia Database" })
            }
            GameKinds.THIS_OR_THAT -> {
                Text("Deck", style = MaterialTheme.typography.labelLarge)
                ChipRow(catalog.thisOrThat.decks.map { it.id to "${it.emoji} ${it.name}" }, deck, onDeck)
            }
        }
        if (k.competitive) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Loser does a truth or dare 😈", style = MaterialTheme.typography.bodyMedium)
                    Muted(if (withPrize) "The winner writes one of each, sealed" else "Just for fun this time")
                }
                Switch(checked = withPrize, onCheckedChange = onPrize)
            }
        }
    }
}

@Composable
private fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(options) { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}
