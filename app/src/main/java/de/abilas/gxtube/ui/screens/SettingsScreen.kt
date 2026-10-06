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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.abilas.gxtube.BuildConfig
import de.abilas.gxtube.GxTubeApp
import de.abilas.gxtube.data.Library
import de.abilas.gxtube.data.Quality
import de.abilas.gxtube.data.SponsorBlock
import de.abilas.gxtube.ui.components.BackTopBar
import de.abilas.gxtube.ui.theme.Yt
import de.abilas.gxtube.ui.theme.YtColors

private val countries = linkedMapOf(
    "DE" to "Deutschland", "AT" to "Österreich", "CH" to "Schweiz", "US" to "USA",
    "GB" to "Großbritannien", "FR" to "Frankreich", "IT" to "Italien", "ES" to "Spanien",
    "NL" to "Niederlande", "PL" to "Polen", "TR" to "Türkei", "IN" to "Indien", "LK" to "Sri Lanka",
)
private val languages = linkedMapOf("de" to "Deutsch", "en" to "English", "ta" to "தமிழ் (Tamil)", "tr" to "Türkçe")
private val themes = linkedMapOf("system" to "Wie das System", "dark" to "Dunkel", "light" to "Hell")

@Composable
fun SettingsScreen() {
    val lib by Library.data.collectAsStateWithLifecycle()
    val s = lib.settings
    var dialog by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        BackTopBar("Einstellungen")
        LazyColumn(contentPadding = PaddingValues(bottom = 80.dp)) {
            item { Header("Wiedergabe") }
            item { ChoiceRow("Videoqualität im WLAN", Quality.label(s.qualityWifi)) { dialog = "wifi" } }
            item { ChoiceRow("Videoqualität mit mobilen Daten", Quality.label(s.qualityMobile)) { dialog = "mobile" } }
            item {
                SwitchRow("Hintergrundwiedergabe", "Ton läuft weiter, wenn du die App verlässt oder den Bildschirm sperrst", s.backgroundPlay) {
                    Library.updateSettings { st -> st.copy(backgroundPlay = it) }
                }
            }
            item {
                SwitchRow("Bild-im-Bild", "Video läuft in einem kleinen Fenster weiter, wenn du zum Startbildschirm wechselst", s.pipOnLeave) {
                    Library.updateSettings { st -> st.copy(pipOnLeave = it) }
                }
            }
            item {
                SwitchRow("Autoplay", "Nach dem Video automatisch ein ähnliches abspielen", s.autoplay) {
                    Library.updateSettings { st -> st.copy(autoplay = it) }
                }
            }

            item { Header("Werbung") }
            item {
                Text(
                    "Videowerbung gibt es in GX Tube nicht: Die Videos werden direkt geladen, ohne die Werbedaten von YouTube.",
                    fontSize = 13.sp,
                    color = Yt.colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            item {
                SwitchRow(
                    "Gesponserte Abschnitte überspringen",
                    "SponsorBlock: von der Community markierte Werbung im Video selbst (\"Dieses Video wird gesponsert von …\")",
                    s.sponsorBlock,
                ) { Library.updateSettings { st -> st.copy(sponsorBlock = it) } }
            }
            if (s.sponsorBlock) {
                item { ChoiceRow("Was übersprungen wird", s.sponsorCategories.joinToString(", ") { SponsorBlock.label(it) }) { dialog = "sponsor" } }
            }

            item { Header("Inhalte") }
            item { ChoiceRow("Land (Trends, Inhalte)", countries[s.country] ?: s.country) { dialog = "country" } }
            item { ChoiceRow("Sprache (Datumsangaben, Titel)", languages[s.language] ?: s.language) { dialog = "language" } }
            item {
                SwitchRow(
                    "Shorts anzeigen",
                    if (s.shortsEnabled) "Aus = Shorts überall ausblenden: Tab, Startseite, Abos, Suche, Kanäle, ähnliche Videos"
                    else "Shorts sind komplett ausgeblendet",
                    s.shortsEnabled,
                ) { Library.updateSettings { st -> st.copy(shortsEnabled = it) } }
            }
            if (s.shortsEnabled) {
                item {
                    SwitchRow("Shorts-Leiste auf der Startseite", null, s.shortsOnHome) {
                        Library.updateSettings { st -> st.copy(shortsOnHome = it) }
                    }
                }
            }

            item { Header("Verlauf") }
            item {
                SwitchRow("Wiedergabeverlauf speichern", "Wird auch für \"Weiter ansehen\" und die Startseite genutzt", s.historyEnabled) {
                    Library.updateSettings { st -> st.copy(historyEnabled = it) }
                }
            }
            item {
                SwitchRow("Suchverlauf speichern", null, s.searchHistoryEnabled) {
                    Library.updateSettings { st -> st.copy(searchHistoryEnabled = it) }
                }
            }
            item { ChoiceRow("Wiedergabeverlauf löschen", "${lib.history.size} Einträge") { dialog = "clear-history" } }
            item { ChoiceRow("Suchverlauf löschen", "${lib.searchHistory.size} Einträge") { Library.clearSearchHistory() } }
            item {
                ChoiceRow("\"Kein Interesse\" zurücksetzen", "${lib.hidden.size} ausgeblendete Videos") {
                    Library.update { it.copy(hidden = emptySet()) }
                }
            }

            item { Header("Design") }
            item { ChoiceRow("Darstellung", themes[s.theme] ?: s.theme) { dialog = "theme" } }

            item { Header("Über") }
            item {
                Text(
                    "GX Tube ${BuildConfig.VERSION_NAME}\n" +
                        "Werbefreie YouTube-App. Videos, Suche und Kanäle kommen über NewPipe Extractor " +
                        "(GPLv3), gesponserte Abschnitte über SponsorBlock. Alle Daten (Abos, Verlauf) bleiben auf dem Handy.",
                    fontSize = 13.sp,
                    color = Yt.colors.textSecondary,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    when (dialog) {
        "wifi" -> RadioDialog("Videoqualität im WLAN", Quality.choices.associateWith { Quality.label(it) }, s.qualityWifi, { dialog = null }) { v ->
            Library.updateSettings { it.copy(qualityWifi = v) }
        }
        "mobile" -> RadioDialog("Videoqualität mit mobilen Daten", Quality.choices.associateWith { Quality.label(it) }, s.qualityMobile, { dialog = null }) { v ->
            Library.updateSettings { it.copy(qualityMobile = v) }
        }
        "country" -> RadioDialog("Land", countries, s.country, { dialog = null }) { v ->
            Library.updateSettings { it.copy(country = v) }
            GxTubeApp.applyRegion()
        }
        "language" -> RadioDialog("Sprache", languages, s.language, { dialog = null }) { v ->
            Library.updateSettings { it.copy(language = v) }
            GxTubeApp.applyRegion()
        }
        "theme" -> RadioDialog("Darstellung", themes, s.theme, { dialog = null }) { v ->
            Library.updateSettings { it.copy(theme = v) }
        }
        "sponsor" -> SponsorDialog(s.sponsorCategories) { dialog = null }
        "clear-history" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Verlauf löschen?") },
            text = { Text("Alle angesehenen Videos und Wiedergabepositionen werden entfernt.") },
            confirmButton = {
                TextButton(onClick = {
                    Library.clearHistory()
                    dialog = null
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun Header(title: String) {
    Column {
        HorizontalDivider(Modifier.padding(top = 8.dp), color = Yt.colors.divider)
        Text(
            title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = YtColors.Red,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = Yt.colors.text)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = Yt.colors.textSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = YtColors.Red),
        )
    }
}

@Composable
private fun ChoiceRow(title: String, value: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, fontSize = 15.sp, color = Yt.colors.text)
        Text(value, fontSize = 13.sp, color = Yt.colors.textSecondary)
    }
}

@Composable
private fun <T> RadioDialog(
    title: String,
    options: Map<T, String>,
    selected: T,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (value, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(value)
                                onDismiss()
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = value == selected,
                            onClick = {
                                onSelect(value)
                                onDismiss()
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = YtColors.Red),
                        )
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun SponsorDialog(selected: Set<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Überspringen") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SponsorBlock.categories.forEach { (key, label) ->
                    val on = key in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                Library.updateSettings { s ->
                                    s.copy(sponsorCategories = if (on) s.sponsorCategories - key else s.sponsorCategories + key)
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = on,
                            onCheckedChange = { checked ->
                                Library.updateSettings { s ->
                                    s.copy(sponsorCategories = if (checked) s.sponsorCategories + key else s.sponsorCategories - key)
                                }
                            },
                            colors = CheckboxDefaults.colors(checkedColor = YtColors.Red),
                        )
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fertig") } },
    )
}
