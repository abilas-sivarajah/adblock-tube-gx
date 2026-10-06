package de.abilas.gxtube.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.abilas.gxtube.data.Library
import de.abilas.gxtube.ui.LocalNav
import de.abilas.gxtube.ui.components.Avatar
import de.abilas.gxtube.ui.components.BackTopBar
import de.abilas.gxtube.ui.components.EmptyBox
import de.abilas.gxtube.ui.components.MenuAction
import de.abilas.gxtube.ui.components.PrimaryPill
import de.abilas.gxtube.ui.components.SubscribeButton
import de.abilas.gxtube.ui.components.VideoRow
import de.abilas.gxtube.ui.theme.Yt
import de.abilas.gxtube.player.PlayerController

/** Verlauf, "Später ansehen" und "Mag ich". */
@Composable
fun LibraryScreen(kind: String) {
    val nav = LocalNav.current
    val lib by Library.data.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    val title = when (kind) {
        "later" -> "Später ansehen"
        "liked" -> "Videos mit \"Mag ich\""
        else -> "Verlauf"
    }
    val videos = when (kind) {
        "later" -> lib.watchLater
        "liked" -> lib.liked
        else -> lib.history.map { it.video }
    }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(title) {
            if (kind == "history" && videos.isNotEmpty()) {
                TextButton(onClick = { confirmClear = true }) { Text("Löschen", color = Yt.colors.text) }
            }
        }
        if (videos.isEmpty()) {
            EmptyBox(
                when (kind) {
                    "later" -> Icons.Outlined.Schedule
                    "liked" -> Icons.Outlined.ThumbUp
                    else -> Icons.Outlined.History
                },
                "Noch leer",
                when (kind) {
                    "later" -> "Tippe bei einem Video auf \"Speichern\", um es hier abzulegen."
                    "liked" -> "Videos, die du mit \"Mag ich\" markierst, landen hier."
                    else -> if (lib.settings.historyEnabled) "Videos, die du ansiehst, erscheinen hier."
                    else "Der Verlauf ist in den Einstellungen pausiert."
                },
            )
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 80.dp)) {
            if (kind != "history") {
                item {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${videos.size} Videos", color = Yt.colors.textSecondary, modifier = Modifier.weight(1f))
                        PrimaryPill("Alle abspielen", onClick = { PlayerController.play(videos.first(), videos) })
                    }
                }
            }
            itemsIndexed(videos, key = { _, v -> v.id }) { index, v ->
                val remove = MenuAction(
                    when (kind) {
                        "later" -> "Aus \"Später ansehen\" entfernen"
                        "liked" -> "Aus \"Mag ich\" entfernen"
                        else -> "Aus dem Verlauf entfernen"
                    },
                    Icons.Outlined.Delete,
                ) {
                    when (kind) {
                        "later" -> Library.toggleWatchLater(v)
                        "liked" -> Library.toggleLike(v)
                        else -> Library.removeFromHistory(v.id)
                    }
                }
                VideoRow(
                    v,
                    onClick = { if (kind == "history") nav.openVideo(v) else PlayerController.play(v, videos) },
                    extraMenu = listOf(remove),
                    index = if (kind == "history") null else index + 1,
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Verlauf löschen?") },
            text = { Text("Alle angesehenen Videos und Wiedergabepositionen werden entfernt.") },
            confirmButton = {
                TextButton(onClick = {
                    Library.clearHistory()
                    confirmClear = false
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Abbrechen") } },
        )
    }
}

/** Alle Abos, alphabetisch, mit Abo-Knopf. */
@Composable
fun ManageSubscriptionsScreen() {
    val nav = LocalNav.current
    val lib by Library.data.collectAsStateWithLifecycle()
    val subs = lib.subscriptions.sortedBy { it.name.lowercase() }
    Column(Modifier.fillMaxSize()) {
        BackTopBar("Abos (${subs.size})")
        LazyColumn(contentPadding = PaddingValues(bottom = 80.dp)) {
            items(subs, key = { it.url }) { s ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { nav.openChannel(s.url) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(s.avatar, s.name, 40.dp)
                    Spacer(Modifier.width(16.dp))
                    Text(s.name, fontSize = 15.sp, color = Yt.colors.text, modifier = Modifier.weight(1f), maxLines = 1)
                    SubscribeButton(subscribed = true, small = true) { Library.unsubscribe(s.url) }
                }
            }
        }
    }
}
