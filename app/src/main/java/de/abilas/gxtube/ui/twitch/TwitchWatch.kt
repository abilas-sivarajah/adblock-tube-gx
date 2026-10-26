package de.abilas.gxtube.ui.twitch

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SpeakerNotesOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import de.abilas.gxtube.cast.Cast
import de.abilas.gxtube.player.NowPlaying
import de.abilas.gxtube.player.PlayerController
import de.abilas.gxtube.twitch.ChatMessage
import de.abilas.gxtube.twitch.ChatPart
import de.abilas.gxtube.twitch.TwitchAccount
import de.abilas.gxtube.twitch.TwitchApi
import de.abilas.gxtube.twitch.TwitchChat
import de.abilas.gxtube.twitch.TwitchPlayer
import de.abilas.gxtube.data.Library
import de.abilas.gxtube.data.VideoItem
import de.abilas.gxtube.ui.LocalNav
import de.abilas.gxtube.ui.components.Avatar
import de.abilas.gxtube.ui.components.LoadingBox
import de.abilas.gxtube.ui.components.PillButton
import de.abilas.gxtube.ui.components.VideoRow
import de.abilas.gxtube.ui.shareText
import de.abilas.gxtube.ui.theme.Yt
import de.abilas.gxtube.ui.theme.YtColors
import de.abilas.gxtube.util.Fmt
import kotlinx.coroutines.delay

/** Unter dem Twitch-Video: Titel, Kanal mit Folgen, bei Live-Streams der Chat, bei VODs weitere Videos. */
@Composable
fun TwitchWatchDetails(n: NowPlaying) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val v = n.video
    val login = v.twitchLogin ?: n.twitch?.login
    val lib by Library.data.collectAsStateWithLifecycle()
    val castState by Cast.state.collectAsStateWithLifecycle()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // Uhr für "seit 2:13 Std."
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(v.id) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    Column(Modifier.fillMaxSize().padding(bottom = navBottom)) {
        // Titel und Kanal
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp)) {
            if (v.title.isNotBlank()) {
                Text(v.title, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium, color = Yt.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
            }
            val since = n.twitch?.startedAt ?: v.uploadedAt.takeIf { v.isLive }
            Text(
                Fmt.joinDot(
                    if (n.isLive) Fmt.watching(v.viewCount) else Fmt.views(v.viewCount),
                    v.game,
                    if (n.isLive && since != null) "live seit " + Fmt.duration((now - since) / 1000) else if (!n.isLive) Fmt.ago(v.uploadedAt) else null,
                ),
                fontSize = 12.sp,
                color = Yt.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(enabled = v.gameName != null) { v.gameName?.let { nav.openTwitchCategory(it) } },
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = login != null) { login?.let { nav.openTwitchChannel(it) } }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(v.channelAvatar, v.channelName, 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(v.channelName, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Yt.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                n.twitch?.followers?.takeIf { it >= 0 }?.let { Text("${Fmt.count(it)} Follower", fontSize = 12.sp, color = Yt.colors.textSecondary) }
            }
            if (login != null) FollowButton(login, n.twitch?.userId, v.channelName, v.channelAvatar)
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = { shareText(context, v.shareUrl) }) { Icon(Icons.Outlined.Share, "Teilen", tint = Yt.colors.text) }
            // Läuft der Stream am PC: Chat dort ein-/ausblenden
            castState?.twitchChat?.let { pcChat ->
                IconButton(onClick = { Cast.setTwitchChat(!pcChat) }) {
                    Icon(
                        if (pcChat) Icons.AutoMirrored.Outlined.Chat else Icons.Outlined.SpeakerNotesOff,
                        if (pcChat) "Chat am PC ausblenden" else "Chat am PC einblenden",
                        tint = if (pcChat) YtColors.Cyan else Yt.colors.text,
                    )
                }
            }
        }
        HorizontalDivider(color = Yt.colors.divider)
        when {
            n.isLive && login != null && lib.settings.twitchChat -> ChatPanel(login, Modifier.weight(1f))
            n.isLive -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                PillButton("Chat einblenden", null, { Library.updateSettings { it.copy(twitchChat = true) } })
            }
            login != null -> MoreVods(login, v.id, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MoreVods(login: String, current: String, modifier: Modifier) {
    val nav = LocalNav.current
    var videos by remember(login) { mutableStateOf<List<VideoItem>?>(null) }
    LaunchedEffect(login) { videos = runCatching { TwitchApi.channelPage(login).videos.items }.getOrDefault(emptyList()) }
    LazyColumn(modifier, contentPadding = PaddingValues(vertical = 8.dp)) {
        val list = videos
        if (list == null) item { LoadingBox() }
        else items(list.filter { it.id != current }, key = { it.id }) { v -> VideoRow(v, onClick = { nav.openVideo(v) }) }
    }
}

// ------------------------------------------------------------ Chat

private val BADGES = mapOf(
    "broadcaster" to ("▶" to Color(0xFFE91916)),
    "moderator" to ("MOD" to Color(0xFF00AD03)),
    "vip" to ("VIP" to Color(0xFFE005B9)),
    "subscriber" to ("★" to TwitchPurple),
)
private val NAME_COLORS = listOf(0xFFFF7F50, 0xFF1E90FF, 0xFF9ACD32, 0xFFFF69B4, 0xFFDAA520, 0xFF5F9EA0, 0xFF00FF7F, 0xFFB22222, 0xFFD2691E, 0xFF8A2BE2, 0xFF2E8B57, 0xFFFF4500)

/** Namensfarbe: Twitchs Farbe (auf dunklem Grund aufgehellt) oder eine feste aus dem Namen. */
private fun nameColor(hex: String?, login: String, dark: Boolean): Color {
    val base = hex?.removePrefix("#")?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
        ?: Color(NAME_COLORS[(login.hashCode() and 0x7fffffff) % NAME_COLORS.size])
    val lum = 0.299f * base.red + 0.587f * base.green + 0.114f * base.blue
    return when {
        dark && lum < 0.4f -> Color(base.red + (1 - base.red) * 0.45f, base.green + (1 - base.green) * 0.45f, base.blue + (1 - base.blue) * 0.45f)
        !dark && lum > 0.7f -> Color(base.red * 0.6f, base.green * 0.6f, base.blue * 0.6f)
        else -> base
    }
}

@Composable
fun ChatPanel(login: String, modifier: Modifier = Modifier) {
    val nav = LocalNav.current
    val messages by TwitchChat.messages.collectAsStateWithLifecycle()
    val state by TwitchChat.state.collectAsStateWithLifecycle()
    val canSend by TwitchChat.canSend.collectAsStateWithLifecycle()
    val account by TwitchAccount.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount == 0 || last >= info.totalItemsCount - 2
        }
    }
    var stick by remember { mutableStateOf(true) }
    var input by remember { mutableStateOf("") }

    // Chat betreten, beim Verlassen der Seite wieder gehen; neu angemeldet: mit Konto neu betreten
    DisposableEffect(login, account?.user?.login, account?.expired) {
        TwitchChat.join(login)
        onDispose { TwitchChat.close() }
    }
    LaunchedEffect(atBottom, listState.isScrollInProgress) { if (listState.isScrollInProgress) stick = atBottom }
    LaunchedEffect(messages.size) { if (stick && messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex) }

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Stream-Chat", color = Yt.colors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(8.dp))
            Text(
                when (state) {
                    TwitchChat.State.OPEN -> if (canSend) "" else "nur lesen"
                    TwitchChat.State.CONNECTING -> "verbinde …"
                    TwitchChat.State.CLOSED -> "getrennt"
                },
                color = Yt.colors.textSecondary,
                fontSize = 12.sp,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 6.dp)) {
                items(messages, key = { it.id }) { m -> ChatLine(m) }
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
            Row(
                Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Yt.colors.chip)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (input.isEmpty()) Text("Nachricht senden", color = Yt.colors.textSecondary, fontSize = 14.sp)
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it.take(500) },
                        singleLine = true,
                        textStyle = TextStyle(color = Yt.colors.text, fontSize = 14.sp),
                        cursorBrush = SolidColor(Yt.colors.text),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (TwitchChat.send(input)) {
                                input = ""
                                stick = true
                            }
                        }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                IconButton(onClick = {
                    if (TwitchChat.send(input)) {
                        input = ""
                        stick = true
                    }
                }) { Icon(Icons.AutoMirrored.Filled.Send, "Senden", tint = TwitchPurple) }
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
}

@Composable
private fun ChatLine(m: ChatMessage) {
    val dark = Yt.colors.isDark
    val textColor = if (m.system) Yt.colors.textSecondary else Yt.colors.text
    val inline = HashMap<String, InlineTextContent>()
    val text: AnnotatedString = buildAnnotatedString {
        if (!m.system) {
            for (b in m.badges) {
                val (label, color) = BADGES[b] ?: continue
                withStyle(SpanStyle(color = Color.White, background = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)) { append(" $label ") }
                append(" ")
            }
            withStyle(SpanStyle(color = nameColor(m.color, m.login, dark), fontWeight = FontWeight.Bold)) { append(m.name) }
            append(if (m.action) " " else ": ")
        }
        val style = SpanStyle(
            color = if (m.action) nameColor(m.color, m.login, dark) else textColor,
            fontStyle = if (m.action || m.system) FontStyle.Italic else FontStyle.Normal,
            textDecoration = if (m.deleted) TextDecoration.LineThrough else null,
        )
        withStyle(style) {
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
                }
            }
        }
    }
    Text(
        text,
        inlineContent = inline,
        fontSize = 13.5.sp,
        lineHeight = 20.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                when {
                    m.mention -> Color(0x33EB0400)
                    m.highlight -> TwitchPurple.copy(alpha = 0.14f)
                    else -> Color.Transparent
                },
            )
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .alpha(if (m.deleted) 0.5f else 1f),
    )
}

// ------------------------------------------------------------ Werbepause

/** Über dem Video, solange der Werbeblocker eine Werbepause überbrückt. */
@Composable
fun TwitchAdOverlay(compact: Boolean, modifier: Modifier = Modifier) {
    val ads by TwitchPlayer.ads.collectAsStateWithLifecycle()
    if (compact || (!ads.masked && !ads.bridge)) return
    val pulse by rememberInfiniteTransition(label = "werbung").animateFloat(1f, 0.3f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "punkt")
    Box(
        modifier
            .fillMaxSize()
            .then(if (ads.masked) Modifier.background(Color.Black) else Modifier),
        contentAlignment = if (ads.masked) Alignment.Center else Alignment.TopEnd,
    ) {
        Row(
            Modifier
                .padding(10.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xCC18181B))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(9.dp)
                    .alpha(pulse)
                    .clip(CircleShape)
                    .background(Color(0xFFBF94FF)),
            )
            Text(
                when {
                    ads.masked && ads.hold -> "Werbepause – GX Tube blendet sie aus"
                    ads.masked -> "Werbung läuft stumm im Hintergrund"
                    else -> "Werbung übersprungen – kurz in 360p"
                },
                color = Color.White,
                fontSize = 13.sp,
            )
        }
    }
}

/** Für die Einstellungen: Hinweis, dass Werbung bei Twitch ausgeblendet wird. */
fun twitchAdNote(): String = "Twitch-Werbung blendet GX Tube mit dem Werbeblocker aus Adblock GX aus: Während einer Werbepause läuft eine " +
    "werbefreie zweite Twitch-Sitzung (kurz in 360p), sonst ein schwarzes Bild."

/** Läuft gerade ein Twitch-Video im Player? */
fun isTwitchPlaying(): Boolean = PlayerController.now.value?.video?.isTwitch == true
