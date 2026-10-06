package de.abilas.gxtube.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import de.abilas.gxtube.ui.theme.Yt
import de.abilas.gxtube.ui.theme.YtColors
import de.abilas.gxtube.update.Updater
import kotlinx.coroutines.launch

/** "Update verfügbar" – Herunterladen und Installieren direkt in der App. */
@Composable
fun UpdateDialog() {
    val state by Updater.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Nach der Rückkehr aus den Android-Einstellungen neu prüfen, ob Installieren erlaubt ist
    var resumes by remember { mutableIntStateOf(0) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { resumes++ }
    }

    when (val s = state) {
        is Updater.State.Available -> {
            val allowed = remember(resumes) { Updater.canInstall(context) }
            AlertDialog(
                onDismissRequest = { Updater.snooze(context) },
                title = { Text("Update verfügbar") },
                text = {
                    Column {
                        Text("GX Tube ${s.release.version} ist da (installiert: ${Updater.currentVersion}).")
                        if (s.release.notes.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text("Neu: ${s.release.notes}", fontSize = 14.sp, color = Yt.colors.textSecondary)
                        }
                        if (!allowed) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Einmalig nötig: Erlaube GX Tube in den Android-Einstellungen, Apps zu installieren. " +
                                    "Danach laufen alle Updates direkt hier.",
                                fontSize = 14.sp,
                            )
                        }
                    }
                },
                confirmButton = {
                    if (allowed) {
                        TextButton(onClick = { scope.launch { Updater.downloadAndInstall(context, s.release) } }) {
                            Text("Jetzt aktualisieren", color = YtColors.Red)
                        }
                    } else {
                        TextButton(onClick = { Updater.openInstallPermission(context) }) {
                            Text("Erlauben", color = YtColors.Red)
                        }
                    }
                },
                dismissButton = { TextButton(onClick = { Updater.snooze(context) }) { Text("Später") } },
            )
        }
        is Updater.State.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Update wird geladen …") },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = { s.progress },
                        color = YtColors.Red,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("${(s.progress * 100).toInt()} % · Version ${s.release.version}", fontSize = 13.sp)
                }
            },
            confirmButton = {},
        )
        is Updater.State.Installing -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Wird installiert …") },
            text = { Text("Android fragt eventuell noch einmal nach. Danach startet GX Tube neu.") },
            confirmButton = { TextButton(onClick = { Updater.dismiss() }) { Text("OK") } },
        )
        is Updater.State.Failed -> AlertDialog(
            onDismissRequest = { Updater.dismiss() },
            title = { Text("Update") },
            text = { Text(s.message) },
            confirmButton = {
                if (s.release != null) {
                    TextButton(onClick = { scope.launch { Updater.downloadAndInstall(context, s.release) } }) {
                        Text("Erneut versuchen")
                    }
                } else {
                    TextButton(onClick = { Updater.dismiss() }) { Text("OK") }
                }
            },
            dismissButton = { if (s.release != null) TextButton(onClick = { Updater.dismiss() }) { Text("Abbrechen") } },
        )
        else -> Unit
    }
}
