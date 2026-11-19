package de.abilas.gxtube.ui.twitch

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import de.abilas.gxtube.data.Library
import de.abilas.gxtube.twitch.ChatCommand
import de.abilas.gxtube.twitch.ChatCommands
import de.abilas.gxtube.twitch.ChatMessage
import de.abilas.gxtube.twitch.ChatPart
import de.abilas.gxtube.twitch.ChatRoom
import de.abilas.gxtube.twitch.ChatUserInfo
import de.abilas.gxtube.twitch.ChatUsers
import de.abilas.gxtube.twitch.TwitchAccount
import de.abilas.gxtube.twitch.TwitchApi
import de.abilas.gxtube.twitch.TwitchChat
import de.abilas.gxtube.ui.LocalNav
import de.abilas.gxtube.ui.components.Avatar
import de.abilas.gxtube.ui.openLink
import de.abilas.gxtube.ui.theme.Yt
import de.abilas.gxtube.ui.watch.SheetRow
import de.abilas.gxtube.util.Fmt
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ------------------------------------------------------------ Farben und Abzeichen

/** Ersatz, solange die Abzeichen-Bilder nicht geladen sind. */
private val BADGES = mapOf(
    "broadcaster" to ("▶" to Color(0xFFE91916)),
    "moderator" to ("MOD" to Color(0xFF00AD03)),
    "vip" to ("VIP" to Color(0xFFE005B9)),
    "subscriber" to ("★" to TwitchPurple),
    "founder" to ("★" to TwitchPurple),
    "staff" to ("STAFF" to Color(0xFF6441A5)),
    "partner" to ("✓" to TwitchPurple),
)
private val NAME_COLORS = listOf(0xFFFF7F50, 0xFF1E90FF, 0xFF9ACD32, 0xFFFF69B4, 0xFFDAA520, 0xFF5F9EA0, 0xFF00FF7F, 0xFFB22222, 0xFFD2691E, 0xFF8A2BE2, 0xFF2E8B57, 0xFFFF4500)

/** Namensfarbe: Twitchs Farbe (auf dunklem Grund aufgehellt) oder eine feste aus dem Namen. */
internal fun nameColor(hex: String?, login: String, dark: Boolean): Color {
    val base = hex?.removePrefix("#")?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
        ?: Color(NAME_COLORS[(login.hashCode() and 0x7fffffff) % NAME_COLORS.size])
    val lum = 0.299f * base.red + 0.587f * base.green + 0.114f * base.blue
    return when {
        dark && lum < 0.4f -> Color(base.red + (1 - base.red) * 0.45f, base.green + (1 - base.green) * 0.45f, base.blue + (1 - base.blue) * 0.45f)
        !dark && lum > 0.7f -> Color(base.red * 0.6f, base.green * 0.6f, base.blue * 0.6f)
        else -> base
    }
}

/** Zuletzt benutzte Emotes (für die Emote-Auswahl, solange die App läuft). */
private object RecentEmotes {
    val names = mutableStateListOf<String>()
    fun used(name: String) {
        names.remove(name)
        names.add(0, name)
        while (names.size > 24) names.removeAt(names.lastIndex)
    }
}

/** Vorschlag beim Tippen: Befehl, Nutzer oder Emote. */
private sealed interface Suggestion {
    val insert: String

    data class Cmd(val cmd: ChatCommand) : Suggestion {
        override val insert get() = "/${cmd.name}"
    }

    data class User(val login: String, val name: String, val at: Boolean) : Suggestion {
        override val insert get() = (if (at) "@" else "") + name
    }

    data class Emote(val name: String, val url: String) : Suggestion {
        override val insert get() = name
    }
}

/** Wort vor dem Cursor: (Anfang, Wort). */
private fun currentWord(v: TextFieldValue): Pair<Int, String> {
    val cursor = v.selection.start.coerceIn(0, v.text.length)
    val before = v.text.substring(0, cursor)
    val start = before.lastIndexOf(' ') + 1
    return start to before.substring(start)
}

private fun suggestionsFor(v: TextFieldValue, me: de.abilas.gxtube.twitch.ChatMe?, emotes: Map<String, String>): List<Suggestion> {
    if (v.selection.length > 0) return emptyList()
    val (start, word) = currentWord(v)
    val text = v.text
    return when {
        start == 0 && word.startsWith("/") && ' ' !in text.substring(0, v.selection.start) ->
            ChatCommands.suggestions(word.drop(1), me).take(30).map { Suggestion.Cmd(it) }
        word.startsWith("@") -> TwitchChat.chatters(word.drop(1)).map { Suggestion.User(it.first, it.second, at = true) }
        // Zweites Wort eines Befehls mit Nutzer (/ban, /timeout …)
        text.startsWith("/") && start > 0 && text.substring(0, start - 1).let { first -> ' ' !in first && ChatCommands.find(first.drop(1))?.user == true } ->
            TwitchChat.chatters(word).map { Suggestion.User(it.first, it.second, at = false) }
        word.startsWith(":") && word.length >= 3 -> {
            val q = word.drop(1).lowercase()
            emotes.entries.asSequence()
                .filter { it.key.lowercase().contains(q) }
                .sortedWith(compareBy({ !it.key.lowercase().startsWith(q) }, { it.key.length }))
                .take(20).map { Suggestion.Emote(it.key, it.value) }.toList()
        }
        else -> emptyList()
    }
}

private fun TextFieldValue.replaceWord(insert: String): TextFieldValue {
    val (start, _) = currentWord(this)
    val cursor = selection.start.coerceIn(0, text.length)
    val after = text.substring(cursor)
    val glue = if (after.startsWith(" ")) "" else " "
    val newText = text.substring(0, start) + insert + glue + after
    val pos = start + insert.length + 1
    return TextFieldValue(newText, TextRange(pos.coerceAtMost(newText.length)))
}

private fun TextFieldValue.insertAtCursor(s: String): TextFieldValue {
    val cursor = selection.start.coerceIn(0, text.length)
    val before = text.substring(0, cursor)
    val after = text.substring(cursor)
    val pre = if (before.isEmpty() || before.endsWith(" ")) "" else " "
    val newText = before + pre + s + " " + after.trimStart()
    val pos = (before + pre + s + " ").length
    return TextFieldValue(newText, TextRange(pos))
}

// ------------------------------------------------------------ Chat

/**
 * Stream-Chat wie in der Twitch-App: Verlauf, Abzeichen, Emotes (Twitch, 7TV, BTTV, FFZ), Antworten,
 * @-Erwähnungen, /-Befehle mit Vorschlägen, Emote-Auswahl, Profilkarten mit Mod-Aktionen.
 * [onClose]: im Querformat neben dem Video – oben ein Schließen-Knopf.
 */
@Composable
fun ChatPanel(login: String, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null) {
    val nav = LocalNav.current
    val messages by TwitchChat.messages.collectAsStateWithLifecycle()
    val state by TwitchChat.state.collectAsStateWithLifecycle()
    val canSend by TwitchChat.canSend.collectAsStateWithLifecycle()
    val room by TwitchChat.room.collectAsStateWithLifecycle()
    val me by TwitchChat.me.collectAsStateWithLifecycle()
    val emotes by TwitchChat.emotes.collectAsStateWithLifecycle()
    val badgeUrls by TwitchChat.badges.collectAsStateWithLifecycle()
    val lastSent by TwitchChat.lastSent.collectAsStateWithLifecycle()
    val account by TwitchAccount.state.collectAsStateWithLifecycle()
    val lib by Library.data.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val orientation = LocalConfiguration.current.orientation

    val listState = rememberLazyListState()
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount == 0 || last >= info.totalItemsCount - 2
        }
    }
    var stick by remember { mutableStateOf(true) }
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var replyTo by remember { mutableStateOf<ChatMessage?>(null) }
    var picker by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<Pair<String, ChatMessage?>?>(null) } // Login, angetippte Nachricht
    val fieldInteraction = remember { MutableInteractionSource() }
    val focused by fieldInteraction.collectIsFocusedAsState()
    val focusRequester = remember { FocusRequester() }

    // Chat betreten; beim Verschwinden der Ansicht (Drehen, Mini-Player) bleibt die Verbindung noch
    // eine Weile – so ist der Chat beim Zurückkommen sofort wieder da
    DisposableEffect(login, account?.user?.login, account?.expired) {
        TwitchChat.join(login)
        onDispose { TwitchChat.release() }
    }
    // Beim Drehen Tastatur schließen – sonst bleibt ihr Platz manchmal reserviert und verdeckt den Chat
    LaunchedEffect(orientation) {
        focusManager.clearFocus()
        keyboard?.hide()
    }
    LaunchedEffect(Unit) { TwitchChat.openUser.collect { sheet = it to null } }
    LaunchedEffect(atBottom, listState.isScrollInProgress) { if (listState.isScrollInProgress) stick = atBottom }
    LaunchedEffect(messages.size, messages.lastOrNull()?.id, stick) { if (stick && messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex) }
    LaunchedEffect(focused) { if (focused) picker = false }

    // Langsamer Modus: Restzeit
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lastSent, room.slow) {
        now = System.currentTimeMillis()
        while (TwitchChat.cooldownLeft(now) > 0) {
            delay(250)
            now = System.currentTimeMillis()
        }
    }
    val cooldown = ((TwitchChat.cooldownLeft(now) + 999) / 1000).toInt()

    fun submit() {
        if (TwitchChat.send(input.text, replyTo)) {
            input = TextFieldValue("")
            replyTo = null
            stick = true
        }
    }

    val suggestions = remember(input, me, emotes) { if (canSend) suggestionsFor(input, me, emotes.all) else emptyList() }

    Column(modifier.fillMaxWidth()) {
        ChatHeader(state, canSend, room, onClose)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 6.dp)) {
                items(messages, key = { it.id }) { m ->
                    ChatLine(
                        m = m,
                        badgeUrls = badgeUrls,
                        showTime = lib.settings.twitchChatTimestamps,
                        iAmMod = me?.isMod == true,
                        myLogin = me?.login,
                        onClick = { if (!m.system && m.login.isNotEmpty()) sheet = m.login to m },
                        onMention = { sheet = it.lowercase() to null },
                    )
                }
            }
            if (!stick && messages.isNotEmpty()) {
                Text(
                    "Neue Nachrichten ↓",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(TwitchPurple)
                        .clickable { stick = true }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
        if (canSend) {
            Column(Modifier.fillMaxWidth().then(if (focused) Modifier.imePadding() else Modifier)) {
                if (suggestions.isNotEmpty()) {
                    SuggestionList(suggestions) { s ->
                        input = input.replaceWord(s.insert)
                        if (s is Suggestion.Emote) RecentEmotes.used(s.name)
                    }
                }
                replyTo?.let { r ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Yt.colors.chip.copy(alpha = 0.6f))
                            .padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Reply, null, tint = Yt.colors.textSecondary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Antwort an @${r.name}: ${r.text}",
                            color = Yt.colors.textSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { replyTo = null }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Filled.Close, "Antwort abbrechen", tint = Yt.colors.textSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        if (picker) {
                            picker = false
                            runCatching { focusRequester.requestFocus() }
                            keyboard?.show()
                        } else {
                            focusManager.clearFocus()
                            keyboard?.hide()
                            picker = true
                        }
                    }) {
                        Icon(if (picker) Icons.Outlined.Keyboard else Icons.Outlined.EmojiEmotions, if (picker) "Tastatur" else "Emotes", tint = Yt.colors.text)
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 40.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Yt.colors.chip)
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (input.text.isEmpty()) {
                            Text(
                                when {
                                    room.emoteOnly && me?.isMod != true -> "Nur Emotes"
                                    replyTo != null -> "Antwort senden"
                                    else -> "Nachricht senden"
                                },
                                color = Yt.colors.textSecondary,
                                fontSize = 14.sp,
                            )
                        }
                        BasicTextField(
                            value = input,
                            onValueChange = { input = if (it.text.length > 500) it.copy(text = it.text.take(500)) else it },
                            maxLines = 4,
                            textStyle = TextStyle(color = Yt.colors.text, fontSize = 14.sp),
                            cursorBrush = SolidColor(Yt.colors.text),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, capitalization = KeyboardCapitalization.Sentences),
                            keyboardActions = KeyboardActions(onSend = { submit() }),
                            interactionSource = fieldInteraction,
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        )
                    }
                    if (cooldown > 0) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            Text("${cooldown}s", color = Yt.colors.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    } else {
                        IconButton(onClick = { submit() }, enabled = input.text.isNotBlank()) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Senden", tint = if (input.text.isNotBlank()) TwitchPurple else Yt.colors.textSecondary)
                        }
                    }
                }
                if (picker && !focused) {
                    EmotePicker(emotes.channel, emotes.twitch, emotes.global) { name ->
                        RecentEmotes.used(name)
                        input = input.insertAtCursor(name)
                    }
                }
            }
        } else if (state == TwitchChat.State.OPEN) {
            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (account?.expired == true) "Twitch-Anmeldung abgelaufen – neu anmelden zum Schreiben" else "Zum Schreiben bei Twitch anmelden",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(TwitchPurple)
                        .clickable { nav.openTwitchLogin() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }

    sheet?.let { (who, msg) ->
        ChatUserSheet(
            login = who,
            message = msg,
            channel = login,
            canSend = canSend,
            iAmMod = me?.isMod == true,
            myLogin = me?.login ?: account?.user?.login,
            onDismiss = { sheet = null },
            onReply = { m ->
                replyTo = m
                sheet = null
                runCatching { focusRequester.requestFocus() }
                keyboard?.show()
            },
            onInsert = { text ->
                input = if (text.startsWith("/")) TextFieldValue(text, TextRange(text.length)) else input.insertAtCursor(text)
                sheet = null
                runCatching { focusRequester.requestFocus() }
                keyboard?.show()
            },
        )
    }
}

@Composable
private fun ChatHeader(state: TwitchChat.State, canSend: Boolean, room: ChatRoom, onClose: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = if (onClose != null) 0.dp else 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Stream-Chat", color = Yt.colors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(8.dp))
        val status = when (state) {
            TwitchChat.State.OPEN -> if (canSend) "" else "nur lesen"
            TwitchChat.State.CONNECTING -> "verbinde …"
            TwitchChat.State.CLOSED -> "getrennt"
        }
        if (status.isNotEmpty()) Text(status, color = Yt.colors.textSecondary, fontSize = 12.sp)
        val chips = buildList {
            if (room.slow > 0) add("Langsam ${room.slow} s")
            if (room.followersOnly == 0) add("Nur Follower")
            else if (room.followersOnly > 0) add("Nur Follower (${minutes(room.followersOnly)})")
            if (room.subsOnly) add("Nur Abos")
            if (room.emoteOnly) add("Nur Emotes")
            if (room.uniqueChat) add("Einzigartig")
        }
        LazyRow(Modifier.weight(1f).padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(chips) { c ->
                Text(
                    c,
                    color = Yt.colors.text,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Yt.colors.chip)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        if (onClose != null) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Chat ausblenden", tint = Yt.colors.text) }
        }
    }
}

private fun minutes(m: Int): String = when {
    m < 60 -> "$m Min."
    m < 1440 -> "${m / 60} Std."
    m < 43_200 -> "${m / 1440} Tage"
    else -> "${m / 43_200} Monate"
}

@Composable
private fun SuggestionList(list: List<Suggestion>, onPick: (Suggestion) -> Unit) {
    HorizontalDivider(color = Yt.colors.divider)
    LazyColumn(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .background(Yt.colors.card),
    ) {
        items(list, key = { it::class.simpleName + it.insert }) { s ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(s) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (s) {
                    is Suggestion.Cmd -> Column {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("/${s.cmd.name}") }
                                if (s.cmd.args.isNotEmpty()) withStyle(SpanStyle(color = Yt.colors.textSecondary)) { append(" ${s.cmd.args}") }
                            },
                            color = Yt.colors.text,
                            fontSize = 14.sp,
                        )
                        Text(s.cmd.description, color = Yt.colors.textSecondary, fontSize = 12.sp)
                    }
                    is Suggestion.User -> Text(
                        (if (s.at) "@" else "") + s.name,
                        color = nameColor(null, s.login, Yt.colors.isDark),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    is Suggestion.Emote -> {
                        AsyncImage(model = s.url, contentDescription = s.name, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(s.name, color = Yt.colors.text, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmotePicker(channel: Map<String, String>, twitch: Map<String, String>, global: Map<String, String>, onPick: (String) -> Unit) {
    val all = remember(channel, twitch, global) { twitch + global + channel }
    val recent = RecentEmotes.names.mapNotNull { n -> all[n]?.let { n to it } }
    val sections = listOf(
        "Zuletzt benutzt" to recent,
        "Kanal (7TV, BTTV, FFZ)" to channel.entries.map { it.key to it.value }.sortedBy { it.first.lowercase() },
        "Twitch" to twitch.entries.map { it.key to it.value },
        "Global (7TV, BTTV, FFZ)" to global.entries.map { it.key to it.value }.sortedBy { it.first.lowercase() },
    ).filter { it.second.isNotEmpty() }
    HorizontalDivider(color = Yt.colors.divider)
    if (sections.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
            Text("Emotes werden geladen …", color = Yt.colors.textSecondary, fontSize = 13.sp)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(46.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(250.dp)
            .background(Yt.colors.card),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
    ) {
        for ((title, list) in sections) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "h-$title") {
                Text(title, color = Yt.colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 4.dp))
            }
            items(list, key = { "$title-${it.first}" }) { (name, url) ->
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onPick(name) }
                        .padding(7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(model = url, contentDescription = name, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.GERMANY)

@Composable
private fun ChatLine(
    m: ChatMessage,
    badgeUrls: Map<String, String>,
    showTime: Boolean,
    iAmMod: Boolean,
    myLogin: String?,
    onClick: () -> Unit,
    onMention: (String) -> Unit,
    /** Chat-Wiedergabe: [ChatMessage.time] ist die Stelle im Video (statt Uhrzeit). */
    replayTime: Boolean = false,
) {
    val context = LocalContext.current
    val nav = LocalNav.current
    val dark = Yt.colors.isDark
    val textColor = if (m.system) Yt.colors.textSecondary else Yt.colors.text
    val inline = HashMap<String, InlineTextContent>()
    // Gelöschte Nachrichten anderer: nur Moderatoren sehen den Text (durchgestrichen)
    val hidden = m.deleted && !iAmMod && !m.self
    val text: AnnotatedString = buildAnnotatedString {
        if (showTime) withStyle(SpanStyle(color = Yt.colors.textSecondary, fontSize = 11.sp)) {
            append((if (replayTime) Fmt.duration(m.time / 1000) else timeFormat.format(Date(m.time))) + " ")
        }
        if (!m.system) {
            for ((i, b) in m.badges.withIndex()) {
                val url = badgeUrls["${b.set}/${b.version}"] ?: badgeUrls["${b.set}/1"]
                if (url != null) {
                    val id = "b$i"
                    inline[id] = InlineTextContent(Placeholder(18.sp, 18.sp, PlaceholderVerticalAlign.Center)) {
                        AsyncImage(model = url, contentDescription = b.set, modifier = Modifier.fillMaxSize())
                    }
                    appendInlineContent(id, "[${b.set}]")
                    append(" ")
                } else {
                    val (label, color) = BADGES[b.set] ?: continue
                    withStyle(SpanStyle(color = Color.White, background = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)) { append(" $label ") }
                    append(" ")
                }
            }
            withStyle(SpanStyle(color = nameColor(m.color, m.login, dark), fontWeight = FontWeight.Bold)) { append(m.name) }
            append(if (m.action) " " else ": ")
        }
        val style = SpanStyle(
            color = if (m.action) nameColor(m.color, m.login, dark) else textColor,
            fontStyle = if (m.action || m.system || hidden) FontStyle.Italic else FontStyle.Normal,
            textDecoration = if (m.deleted && !hidden) TextDecoration.LineThrough else null,
        )
        withStyle(style) {
            if (hidden) {
                withStyle(SpanStyle(color = Yt.colors.textSecondary)) { append("‹Nachricht gelöscht›") }
                return@withStyle
            }
            m.parts.forEachIndexed { i, p ->
                when (p) {
                    is ChatPart.Text -> append(p.text)
                    is ChatPart.Emote -> {
                        val id = "e$i"
                        inline[id] = InlineTextContent(Placeholder(26.sp, 26.sp, PlaceholderVerticalAlign.Center)) {
                            AsyncImage(model = p.url, contentDescription = p.name, modifier = Modifier.fillMaxSize())
                        }
                        appendInlineContent(id, p.name)
                    }
                    is ChatPart.Mention -> {
                        val me = myLogin != null && p.name.equals(myLogin, ignoreCase = true)
                        withLink(
                            LinkAnnotation.Clickable(
                                "mention-${p.name}",
                                TextLinkStyles(SpanStyle(fontWeight = FontWeight.Bold, background = if (me) Color(0x55EB0400) else Color.Unspecified)),
                            ) { onMention(p.name) },
                        ) { append("@${p.name}") }
                    }
                    is ChatPart.Link -> withLink(
                        LinkAnnotation.Clickable("link-$i", TextLinkStyles(SpanStyle(color = Yt.colors.link, textDecoration = TextDecoration.Underline))) {
                            val url = if (p.url.startsWith("http", true)) p.url else "https://${p.url}"
                            val twitchLogin = Regex("^https?://(www\\.|m\\.)?twitch\\.tv/([a-z0-9_]{3,25})/?$", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(2)
                            if (twitchLogin != null && TwitchApi.validLogin(twitchLogin)) nav.openTwitchChannel(twitchLogin.lowercase())
                            else openLink(context, nav, url)
                        },
                    ) { append(p.url) }
                }
            }
        }
    }
    val background = when {
        m.mention -> Color(0x33EB0400)
        m.highlight -> TwitchPurple.copy(alpha = 0.14f)
        else -> Color.Transparent
    }
    val accent = m.accent?.let { Color(it) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(background)
            // Farbiger Rand links (Ankündigungen, Abos, erste Nachricht …)
            .then(if (accent != null) Modifier.drawBehind { drawRect(accent, size = Size(3.dp.toPx(), size.height)) } else Modifier)
            .clickable(enabled = !m.system && m.login.isNotEmpty(), onClick = onClick)
            .alpha(if (m.deleted) 0.5f else if (m.history) 0.75f else 1f),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 2.dp)) {
            m.label?.let {
                Text(it, color = Yt.colors.textSecondary, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            m.reply?.let { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.Reply, null, tint = Yt.colors.textSecondary, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Antwort an @${r.name}: ${r.text}",
                        color = Yt.colors.textSecondary,
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(text, inlineContent = inline, fontSize = 13.5.sp, lineHeight = 20.sp)
        }
    }
}

// ------------------------------------------------------------ Profilkarte

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatUserSheet(
    login: String,
    message: ChatMessage?,
    channel: String,
    canSend: Boolean,
    iAmMod: Boolean,
    myLogin: String?,
    onDismiss: () -> Unit,
    onReply: (ChatMessage) -> Unit,
    onInsert: (String) -> Unit,
) {
    val context = LocalContext.current
    val nav = LocalNav.current
    val messages by TwitchChat.messages.collectAsStateWithLifecycle()
    val badgeUrls by TwitchChat.badges.collectAsStateWithLifecycle()
    var info by remember(login) { mutableStateOf<ChatUserInfo?>(null) }
    var showAll by remember(login) { mutableStateOf(false) }
    LaunchedEffect(login) { info = runCatching { ChatUsers.load(login, channel) }.getOrNull() }
    val theirs = remember(messages, login) { messages.filter { it.login == login && !it.system } }
    val last = message ?: theirs.lastOrNull()
    val name = info?.name ?: last?.name ?: login
    val isSelf = myLogin != null && login == myLogin
    val canModerate = iAmMod && !isSelf && login != channel
    val dateFormat = remember { SimpleDateFormat("d. MMMM yyyy", Locale.GERMANY) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Yt.colors.card) {
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(info?.avatar, name, 56.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            last?.badges?.forEach { b ->
                                val url = badgeUrls["${b.set}/${b.version}"] ?: badgeUrls["${b.set}/1"]
                                if (url != null) {
                                    AsyncImage(model = url, contentDescription = b.set, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                }
                            }
                            Text(name, color = nameColor(last?.color, login, Yt.colors.isDark), fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        val lines = listOfNotNull(
                            info?.createdAt?.let { "Konto erstellt am ${dateFormat.format(Date(it))}" },
                            info?.followedAt?.let { "Folgt seit ${dateFormat.format(Date(it))}" },
                            info?.followers?.let { "${Fmt.count(it)} Follower" },
                        )
                        for (l in lines) Text(l, color = Yt.colors.textSecondary, fontSize = 12.sp)
                    }
                }
                info?.description?.let {
                    Text(it, color = Yt.colors.textSecondary, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                if (message != null) {
                    Text(
                        message.text,
                        color = Yt.colors.text,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Yt.colors.chip)
                            .padding(10.dp),
                    )
                }
                HorizontalDivider(color = Yt.colors.divider)
            }
            if (canSend && message != null && message.canReply) {
                item { SheetRow(Icons.AutoMirrored.Filled.Reply, "Antworten") { onReply(message) } }
            }
            if (canSend && !isSelf) {
                item { SheetRow(Icons.Outlined.AlternateEmail, "Erwähnen") { onInsert("@$name") } }
                item { SheetRow(Icons.Outlined.Email, "Flüstern") { onInsert("/w $login ") } }
            }
            if (message != null && message.text.isNotEmpty()) {
                item {
                    SheetRow(Icons.Outlined.ContentCopy, "Nachricht kopieren") {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Chat", message.text))
                        Toast.makeText(context, "Kopiert", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                }
            }
            item {
                SheetRow(Icons.Outlined.Person, "Kanal von $name öffnen") {
                    onDismiss()
                    nav.openTwitchChannel(login)
                }
            }
            if (theirs.isNotEmpty()) {
                item { SheetRow(Icons.Outlined.Forum, "Nachrichten im Chat", "${theirs.size}") { showAll = !showAll } }
                if (showAll) {
                    items(theirs.takeLast(50).asReversed(), key = { "u-" + it.id }) { m ->
                        Text(
                            timeFormat.format(Date(m.time)) + "  " + m.text,
                            color = if (m.deleted) Yt.colors.textSecondary else Yt.colors.text,
                            fontSize = 13.sp,
                            textDecoration = if (m.deleted) TextDecoration.LineThrough else null,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            if (canModerate) {
                item {
                    Text("Moderation", color = Yt.colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp))
                }
                if (message != null && message.canReply && !message.deleted) {
                    item {
                        SheetRow(Icons.Outlined.Delete, "Nachricht löschen") {
                            ChatCommands.deleteMessage(message.id)
                            onDismiss()
                        }
                    }
                }
                for ((label, secs) in listOf("Timeout 1 Minute" to 60, "Timeout 10 Minuten" to 600, "Timeout 1 Stunde" to 3600, "Timeout 1 Tag" to 86_400)) {
                    item {
                        SheetRow(Icons.Outlined.Timer, label) {
                            ChatCommands.timeout(login, secs)
                            onDismiss()
                        }
                    }
                }
                item {
                    SheetRow(Icons.Outlined.Gavel, "Bannen") {
                        ChatCommands.timeout(login, null)
                        onDismiss()
                    }
                }
                item {
                    SheetRow(Icons.Outlined.LockOpen, "Bann/Timeout aufheben") {
                        ChatCommands.unban(login)
                        onDismiss()
                    }
                }
            }
            if (canSend && !isSelf) {
                item {
                    SheetRow(Icons.Outlined.Block, "$name blockieren") {
                        ChatCommands.run("block", login)
                        onDismiss()
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------ Chat-Wiedergabe (VODs)

/**
 * Chat eines vergangenen Streams, passend zur Stelle im Video (wie auf twitch.tv).
 * [roomId]: Kanal-Nummer für die 7TV/BTTV/FFZ-Emotes.
 */
@Composable
fun ChatReplayPanel(vod: String, channel: String, roomId: String?, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null) {
    val lib by Library.data.collectAsStateWithLifecycle()
    val ps = de.abilas.gxtube.ui.watch.rememberPlayerState()
    val listState = rememberLazyListState()
    var shown by remember(vod) { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var error by remember(vod) { mutableStateOf<String?>(null) }
    var loading by remember(vod) { mutableStateOf(true) }
    var badgeUrls by remember(channel) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var extra by remember(roomId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var stick by remember { mutableStateOf(true) }
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount == 0 || last >= info.totalItemsCount - 2
        }
    }
    // Aktuelle Stelle für die Ladeschleife (ohne sie bei jeder Änderung neu zu starten)
    val position = remember { java.util.concurrent.atomic.AtomicLong(0) }
    position.set(ps.position)

    LaunchedEffect(channel) { badgeUrls = runCatching { de.abilas.gxtube.twitch.TwitchBadges.load(channel) }.getOrDefault(emptyMap()) }
    LaunchedEffect(roomId) {
        if (roomId != null) extra = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { de.abilas.gxtube.twitch.ThirdPartyEmotes.load(roomId).let { (c, g) -> g + c } }
        }.getOrDefault(emptyMap())
    }
    LaunchedEffect(vod, extra) {
        val buffer = ArrayList<de.abilas.gxtube.twitch.ReplayMessage>()
        var cursor: String? = null
        var start = -1
        var failures = 0
        while (true) {
            val pos = (position.get() / 1000).toInt()
            try {
                // Gesprungen (oder Anfang): ab hier neu laden
                if (buffer.isEmpty() || pos < start - 2 || pos > (buffer.lastOrNull()?.offset ?: 0) + 90) {
                    val from = (pos - 30).coerceAtLeast(0)
                    val page = de.abilas.gxtube.twitch.TwitchChatReplay.page(vod, from, null, extra)
                    buffer.clear()
                    buffer += page.messages
                    cursor = page.next
                    start = from
                }
                // Etwas vorausladen
                var rounds = 0
                while (cursor != null && (buffer.lastOrNull()?.offset ?: 0) < pos + 20 && rounds++ < 5) {
                    val page = de.abilas.gxtube.twitch.TwitchChatReplay.page(vod, null, cursor, extra)
                    buffer += page.messages
                    cursor = page.next
                }
                // Alte Nachrichten weit hinter der Stelle vergessen
                if (buffer.size > 3000) {
                    val keep = buffer.filter { it.offset >= pos - 600 }
                    buffer.clear()
                    buffer += keep
                    start = buffer.firstOrNull()?.offset ?: pos
                }
                shown = buffer.asSequence().filter { it.offset <= pos }.map { it.message }.distinctBy { it.id }.toList().takeLast(200)
                error = null
                failures = 0
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failures++
                if (shown.isEmpty()) error = e.message ?: "Chat konnte nicht geladen werden"
            }
            loading = false
            delay(if (failures > 0) (2000L * failures).coerceAtMost(30_000) else 700)
        }
    }
    LaunchedEffect(atBottom, listState.isScrollInProgress) { if (listState.isScrollInProgress) stick = atBottom }
    LaunchedEffect(shown.lastOrNull()?.id, stick) { if (stick && shown.isNotEmpty()) listState.scrollToItem(shown.lastIndex) }

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = if (onClose != null) 0.dp else 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Chat-Wiedergabe", color = Yt.colors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(8.dp))
            Text(if (loading) "lädt …" else "läuft mit dem Video", color = Yt.colors.textSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
            if (onClose != null) IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Chat ausblenden", tint = Yt.colors.text) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                error != null && shown.isEmpty() -> Text(error.orEmpty(), color = Yt.colors.textSecondary, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center).padding(24.dp))
                !loading && shown.isEmpty() -> Text("Noch keine Nachrichten an dieser Stelle", color = Yt.colors.textSecondary, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center).padding(24.dp))
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 6.dp)) {
                items(shown, key = { it.id }) { m ->
                    ChatLine(
                        m = m,
                        badgeUrls = badgeUrls,
                        showTime = lib.settings.twitchChatTimestamps,
                        iAmMod = false,
                        myLogin = null,
                        onClick = {},
                        onMention = {},
                        replayTime = true,
                    )
                }
            }
            if (!stick && shown.isNotEmpty()) {
                Text(
                    "Zur aktuellen Stelle ↓",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(TwitchPurple)
                        .clickable { stick = true }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}
