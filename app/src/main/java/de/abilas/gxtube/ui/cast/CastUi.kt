package de.abilas.gxtube.ui.cast

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import de.abilas.gxtube.cast.Cast
import de.abilas.gxtube.cast.CastConnection
import de.abilas.gxtube.cast.CastDevice
import de.abilas.gxtube.player.NowPlaying
import de.abilas.gxtube.player.PlayerController
import de.abilas.gxtube.ui.theme.Yt
import de.abilas.gxtube.ui.theme.YtColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

private const val DESKTOP_DOWNLOAD = "https://github.com/abilas-sivarajah/adblock-tube-gx-desktop/releases/latest"

/** Geräteauswahl von außen öffnen (z. B. adb shell am start … --es gxtube_route cast). */
object CastLaunch {
    val open = MutableStateFlow(false)
}

/** Streamen-Symbol wie in der YouTube-App: grau = bereit, gefüllt = verbunden. */
@Composable
fun CastButton(tint: Color = Yt.colors.text) {
    var open by rememberSaveable { mutableStateOf(false) }
    val connection by Cast.connection.collectAsStateWithLifecycle()
    IconButton(onClick = { open = true }) {
        when (connection) {
            is CastConnection.Connected -> Icon(Icons.Filled.CastConnected, "Verbunden – Streamen", tint = YtColors.Cyan)
            is CastConnection.Connecting -> Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Cast, "Verbinde …", tint = tint.copy(alpha = 0.5f))
                CircularProgressIndicator(color = tint, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            }
            CastConnection.Disconnected -> Icon(Icons.Filled.Cast, "Auf PC streamen", tint = tint)
        }
    }
    if (open) CastSheet(onDismiss = { open = false })
}

/** Gerät auswählen, Lautstärke, Trennen. */
@Composable
fun CastSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val connection by Cast.connection.collectAsStateWithLifecycle()
    // Material3 1.3: Ändert sich die Höhe, während das Blatt aufklappt (Suche startet, PC gefunden),
    // bricht das Aufklappen ab und das Blatt bleibt unsichtbar → dann einfach noch einmal aufklappen.
    LaunchedEffect(sheetState) {
        delay(600)
        if (!sheetState.isVisible) sheetState.show()
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Yt.colors.card) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            when (val c = connection) {
                is CastConnection.Connected -> ConnectedPanel(c.device, onDismiss)
                else -> DevicePicker(connecting = (c as? CastConnection.Connecting)?.device, onConnected = onDismiss)
            }
        }
    }
}

@Composable
private fun ConnectedPanel(device: CastDevice, onDismiss: () -> Unit) {
    val state by Cast.state.collectAsStateWithLifecycle()
    val c = Yt.colors
    Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CastConnected, null, tint = YtColors.Cyan, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(device.name, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Text(
                state?.video?.title?.takeIf { it.isNotBlank() }?.let { "▶ $it" } ?: "Verbunden – wähle ein Video aus",
                color = c.textSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    // Lautstärke am PC
    val volume = state?.volume ?: 100
    val muted = state?.muted ?: false
    var dragging by remember { mutableStateOf<Float?>(null) }
    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { Cast.setMuted(!muted) }) {
            Icon(if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, "Stumm", tint = c.text)
        }
        Slider(
            value = dragging ?: if (muted) 0f else volume.toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { Cast.setVolume(it.toInt()) }
                dragging = null
            },
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(thumbColor = YtColors.Red, activeTrackColor = YtColors.Red),
            modifier = Modifier.weight(1f),
        )
        Text("${(dragging ?: volume.toFloat()).toInt()} %", color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
        Spacer(Modifier.width(8.dp))
    }
    Text(
        "Die Lautstärketasten des Handys steuern jetzt die Lautstärke am PC.",
        color = c.textSecondary,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
    Spacer(Modifier.height(16.dp))

    if (state?.video != null) {
        SheetAction(Icons.Outlined.PhoneAndroid, "Auf dem Handy weiterschauen", "Am PC anhalten und hier an derselben Stelle fortsetzen") {
            PlayerController.disconnectCast(continueHere = true)
            onDismiss()
        }
    }
    SheetAction(Icons.Filled.Cast, "Verbindung trennen", if (state?.video != null) "Das Video läuft am PC weiter" else null) {
        PlayerController.disconnectCast(continueHere = false)
        onDismiss()
    }
}

@Composable
private fun DevicePicker(connecting: CastDevice?, onConnected: () -> Unit) {
    val c = Yt.colors
    val context = LocalContext.current
    val devices by Cast.devices.collectAsStateWithLifecycle()
    val scanning by Cast.scanning.collectAsStateWithLifecycle()
    val connection by Cast.connection.collectAsStateWithLifecycle()
    var manual by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    var wasConnecting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { Cast.scan() }
    LaunchedEffect(connection) {
        if (connection is CastConnection.Connecting) wasConnecting = true
        if (connection is CastConnection.Connected && wasConnecting) onConnected()
    }

    // Feste Höhen, damit das Blatt beim Suchen nicht springt (siehe oben)
    Row(Modifier.padding(start = 20.dp, end = 8.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Auf Gerät streamen", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (scanning) {
                CircularProgressIndicator(color = c.textSecondary, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            } else {
                IconButton(onClick = { Cast.scan() }) { Icon(Icons.Outlined.Refresh, "Erneut suchen", tint = c.text) }
            }
        }
    }

    val last = Cast.lastDevice
    val list = buildList {
        addAll(devices)
        if (last != null && devices.none { it.id == last.id }) add(last)
    }
    Column(Modifier.fillMaxWidth().heightIn(min = 140.dp)) {
        list.forEach { d ->
            val busy = connecting?.id == d.id
            DeviceRow(d, busy = busy, remembered = devices.none { it.id == d.id }) { Cast.connect(d) }
        }
        if (connecting != null && list.none { it.id == connecting.id }) DeviceRow(connecting, busy = true, remembered = false) {}

        if (list.isEmpty() && !scanning) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("Kein PC gefunden", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Auf dem PC muss GX Tube Desktop laufen, und Handy und PC müssen im selben WLAN sein. " +
                        "Unter Windows beim ersten Start den Zugriff für private Netzwerke erlauben.",
                    color = c.textSecondary,
                    fontSize = 13.sp,
                )
            }
        }
        if (list.isEmpty() && scanning) {
            Text("Suche im WLAN …", color = c.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        }
    }

    Spacer(Modifier.height(8.dp))
    if (!manual) {
        SheetAction(Icons.Outlined.Wifi, "Per Adresse verbinden", "Die Adresse steht in GX Tube Desktop auf der Startseite") { manual = true }
    } else {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it.trim() },
                label = { Text("IP-Adresse des PCs") },
                placeholder = { Text("192.168.178.23") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (address.isNotBlank()) Cast.connectAddress(address) }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { Cast.connectAddress(address) }, enabled = address.isNotBlank()) { Text("Verbinden") }
        }
    }
    SheetAction(Icons.Outlined.Download, "GX Tube Desktop herunterladen", "Für Windows, Linux und macOS") {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DESKTOP_DOWNLOAD))) }
    }
}

@Composable
private fun DeviceRow(device: CastDevice, busy: Boolean, remembered: Boolean, onClick: () -> Unit) {
    val c = Yt.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Computer, null, tint = c.text)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(device.name, color = c.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (remembered) "Zuletzt verbunden · ${device.address}" else device.address,
                color = c.textSecondary,
                fontSize = 12.sp,
            )
        }
        if (busy) CircularProgressIndicator(color = c.text, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SheetAction(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String?, onClick: () -> Unit) {
    val c = Yt.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.text)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = c.text, fontSize = 15.sp)
            if (detail != null) Text(detail, color = c.textSecondary, fontSize = 12.sp)
        }
    }
}

/** Ersetzt das Videobild, solange das Video am PC läuft. */
@Composable
fun CastSurface(now: NowPlaying, modifier: Modifier = Modifier, compact: Boolean = false) {
    val connection by Cast.connection.collectAsStateWithLifecycle()
    val name = when (val c = connection) {
        is CastConnection.Connected -> c.device.name
        is CastConnection.Connecting -> c.device.name
        CastConnection.Disconnected -> "PC"
    }
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = now.video.thumbnailOrDefault,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(if (compact) 0.dp else 6.dp)
                .alpha(0.45f),
        )
        if (!compact) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 24.dp)) {
                Icon(Icons.Filled.CastConnected, null, tint = YtColors.Cyan, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(8.dp))
                Text(
                    if (connection is CastConnection.Connecting) "Verbindung zu $name wird wiederhergestellt …" else "Wird auf $name abgespielt",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Icon(Icons.Filled.CastConnected, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

/** Kurz eingeblendete PC-Lautstärke nach Druck auf die Lautstärketasten. */
@Composable
fun CastVolumeHint(modifier: Modifier = Modifier) {
    val hint by Cast.volumeHint.collectAsStateWithLifecycle()
    var last by remember { mutableFloatStateOf(0f) }
    hint?.let { last = it.toFloat() }
    AnimatedVisibility(visible = hint != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Row(
            Modifier
                .statusBarsPadding()
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xE6212121))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Filled.CastConnected, null, tint = YtColors.Cyan, modifier = Modifier.size(18.dp))
            Icon(if (last <= 0f) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, null, tint = Color.White, modifier = Modifier.size(18.dp))
            Box(
                Modifier
                    .width(120.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.3f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(last / 100f)
                        .height(4.dp)
                        .background(Color.White),
                )
            }
            Text("${last.toInt()}", color = Color.White, fontSize = 13.sp, modifier = Modifier.width(28.dp))
        }
    }
}
