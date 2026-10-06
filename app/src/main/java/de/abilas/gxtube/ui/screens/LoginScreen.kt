package de.abilas.gxtube.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import de.abilas.gxtube.data.Account
import de.abilas.gxtube.data.Sync
import de.abilas.gxtube.ui.LocalNav
import de.abilas.gxtube.ui.components.BackTopBar
import de.abilas.gxtube.ui.theme.Yt
import de.abilas.gxtube.ui.theme.YtColors
import kotlinx.coroutines.launch

private const val LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?service=youtube&hl=de&passive=true" +
        "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Dm%26hl%3Dde%26next%3D%252F"

/** Google-Anmeldeseite in der App. Nach dem Login werden nur die youtube.com-Cookies übernommen. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen() {
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        BackTopBar("Mit Google anmelden")
        Text(
            "Du meldest dich direkt bei Google an. GX Tube sieht dein Passwort nicht, sondern übernimmt nur " +
                "die Anmeldung für youtube.com – sie bleibt auf diesem Handy. Videos laufen weiter ohne Werbung.",
            fontSize = 12.sp,
            color = Yt.colors.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        error?.let {
            Text(
                it,
                fontSize = 13.sp,
                color = YtColors.Red,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            AndroidView(
                factory = { ctx ->
                    val cookies = CookieManager.getInstance()
                    cookies.removeAllCookies(null)
                    cookies.setAcceptCookie(true)
                    WebView(ctx).apply {
                        cookies.setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Ohne "wv" im User-Agent lässt Google die Anmeldung in der App zu
                        settings.userAgentString = settings.userAgentString
                            .replace("; wv", "")
                            .replace(Regex("""Version/\S+\s"""), "")
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                if (busy || url == null) return
                                if (!url.startsWith("https://www.youtube.com") && !url.startsWith("https://m.youtube.com")) return
                                val all = cookies.getCookie("https://www.youtube.com") ?: return
                                if (!all.contains("SAPISID")) return
                                cookies.flush()
                                busy = true
                                scope.launch {
                                    runCatching {
                                        Account.login(all)
                                        Sync.importSubscriptions()
                                    }.onSuccess { added ->
                                        val name = Account.state.value?.name
                                        Toast.makeText(
                                            context,
                                            "Angemeldet${name?.let { " als $it" } ?: ""}" +
                                                if (added > 0) " · $added Abos übernommen" else "",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                        nav.back()
                                    }.onFailure {
                                        // Anmeldung gespeichert, nur der Abgleich ging schief
                                        if (Account.loggedIn) {
                                            Toast.makeText(context, "Angemeldet (Abos werden später abgeglichen)", Toast.LENGTH_LONG).show()
                                            nav.back()
                                        } else {
                                            error = "Anmeldung fehlgeschlagen: ${it.message}"
                                            busy = false
                                        }
                                    }
                                }
                            }
                        }
                        loadUrl(LOGIN_URL)
                    }
                },
                onRelease = { it.destroy() },
                modifier = Modifier.fillMaxSize(),
            )
            if (busy) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Text("Konto wird verbunden …", color = Color.White, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }
    }
}

/** Abmelden: Konto-Daten und Cookies der Anmeldeseite löschen. */
fun logoutEverywhere() {
    Account.logout()
    CookieManager.getInstance().removeAllCookies(null)
}
