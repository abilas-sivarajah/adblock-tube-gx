package de.abilas.gxtube.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Help
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.abilas.gxtube.BuildConfig
import de.abilas.gxtube.data.Library
import de.abilas.gxtube.data.SubscriptionFeed
import de.abilas.gxtube.data.YouTubeRepo
import de.abilas.gxtube.ui.LocalNav
import de.abilas.gxtube.ui.components.SectionHeader
import de.abilas.gxtube.ui.components.Thumbnail
import de.abilas.gxtube.ui.theme.Yt
import de.abilas.gxtube.ui.theme.YtColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun YouScreen() {
    val nav = LocalNav.current
    val context = LocalContext.current
    val lib by Library.data.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val result = runCatching {
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                val subs = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.use { YouTubeRepo.importSubscriptions(it, name) }
                }
                Library.addSubscriptions(subs) to subs.size
            }
            importing = false
            result.onSuccess { (added, total) ->
                Toast.makeText(context, "$added neue Abos übernommen ($total in der Datei)", Toast.LENGTH_LONG).show()
                launch { SubscriptionFeed.refresh(force = true) }
                launch { SubscriptionFeed.fillMissingAvatars(200) }
            }.onFailure {
                Toast.makeText(context, "Import fehlgeschlagen: ${it.message ?: it.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)!!.use {
                        it.write(YouTubeRepo.exportSubscriptionsJson(Library.data.value.subscriptions).toByteArray())
                    }
                }
            }.isSuccess
            Toast.makeText(context, if (ok) "Abos exportiert" else "Export fehlgeschlagen", Toast.LENGTH_SHORT).show()
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(top = statusTop, bottom = 80.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(YtColors.Red, YtColors.Cyan))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Du", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Du", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Yt.colors.text)
                    Text(
                        "Lokales Profil · ohne Google-Konto · werbefrei",
                        fontSize = 12.sp,
                        color = Yt.colors.textSecondary,
                    )
                }
                IconButton(onClick = { nav.openSettings() }) {
                    Icon(Icons.Outlined.Settings, "Einstellungen", tint = Yt.colors.text)
                }
            }
        }

        item {
            SectionHeader("Verlauf", action = "Alle ansehen", onAction = { nav.openLibrary("history") })
        }
        item {
            val history = lib.history.take(20)
            if (history.isEmpty()) {
                Text(
                    "Hier erscheinen Videos, die du ansiehst.",
                    color = Yt.colors.textSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(history, key = { it.video.id }) { h ->
                        Column(
                            Modifier
                                .width(160.dp)
                                .clickable { nav.openVideo(h.video) },
                        ) {
                            Thumbnail(h.video, Modifier.fillMaxWidth(), corner = 8.dp)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                h.video.title,
                                fontSize = 13.sp,
                                lineHeight = 17.sp,
                                color = Yt.colors.text,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(h.video.channelName, fontSize = 12.sp, color = Yt.colors.textSecondary, maxLines = 1)
                        }
                    }
                }
            }
        }

        item { SectionHeader("Playlists") }
        item {
            PlaylistEntry(Icons.Outlined.Schedule, "Später ansehen", "${lib.watchLater.size} Videos") {
                nav.openLibrary("later")
            }
        }
        item {
            PlaylistEntry(Icons.Outlined.ThumbUp, "Videos mit \"Mag ich\"", "${lib.liked.size} Videos") {
                nav.openLibrary("liked")
            }
        }

        item { HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Yt.colors.divider) }
        item {
            MenuEntry(Icons.Outlined.Subscriptions, "Deine Abos (${lib.subscriptions.size})") { nav.openManageSubscriptions() }
        }
        item {
            MenuEntry(Icons.Outlined.Download, if (importing) "Abos werden importiert …" else "Abos importieren (Google Takeout / NewPipe)") {
                if (!importing) importLauncher.launch(arrayOf("*/*"))
            }
        }
        item {
            MenuEntry(Icons.Outlined.Upload, "Abos exportieren") { exportLauncher.launch("gxtube_abos.json") }
        }
        item { MenuEntry(Icons.Outlined.History, "Verlauf verwalten") { nav.openLibrary("history") } }
        item { MenuEntry(Icons.Outlined.Settings, "Einstellungen") { nav.openSettings() } }
        item { MenuEntry(Icons.AutoMirrored.Outlined.Help, "So holst du deine YouTube-Abos") { showHelp = true } }
        item {
            Text(
                "GX Tube ${BuildConfig.VERSION_NAME} · werbefrei dank NewPipe Extractor",
                fontSize = 12.sp,
                color = Yt.colors.textSecondary,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("OK") } },
            title = { Text("YouTube-Abos übernehmen") },
            text = {
                Text(
                    "1. Im Browser takeout.google.com öffnen und mit deinem Google-Konto anmelden.\n" +
                        "2. \"Alle abwählen\", dann nur \"YouTube und YouTube Music\" auswählen.\n" +
                        "3. Bei \"Alle YouTube-Daten enthalten\" nur \"Abos\" anhaken.\n" +
                        "4. Export erstellen und die ZIP-Datei herunterladen.\n" +
                        "5. Hier \"Abos importieren\" tippen und die ZIP-Datei (oder subscriptions.csv) auswählen.",
                )
            },
        )
    }
}

@Composable
private fun PlaylistEntry(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Yt.colors.chip),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = Yt.colors.text)
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, fontSize = 15.sp, color = Yt.colors.text)
            Text(subtitle, fontSize = 12.sp, color = Yt.colors.textSecondary)
        }
    }
}

@Composable
fun MenuEntry(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Yt.colors.text)
        Spacer(Modifier.width(24.dp))
        Text(title, fontSize = 15.sp, color = Yt.colors.text)
    }
}
