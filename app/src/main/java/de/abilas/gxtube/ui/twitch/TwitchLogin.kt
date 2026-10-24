package de.abilas.gxtube.ui.twitch

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.abilas.gxtube.twitch.TwitchAccount
import de.abilas.gxtube.twitch.TwitchApi
import de.abilas.gxtube.ui.LocalNav
import de.abilas.gxtube.ui.components.BackTopBar
import de.abilas.gxtube.ui.theme.Yt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class TwitchLoginViewModel : ViewModel() {
    var code by mutableStateOf<TwitchApi.DeviceCode?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var done by mutableStateOf(false)
        private set
    var inApp by mutableStateOf(false)
    private var job: Job? = null

    init {
        start()
    }

    fun start() {
        job?.cancel()
        error = null
        code = null
        job = viewModelScope.launch {
            try {
                val c = TwitchApi.startDeviceLogin()
                code = c
                val tokens = TwitchApi.awaitDeviceLogin(c)
                TwitchAccount.login(tokens)
                done = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: TwitchApi.DeviceLoginException) {
                error = e.message
                inApp = false
            } catch (t: Throwable) {
                error = "Das hat nicht geklappt: ${t.message}"
                inApp = false
            }
        }
    }
}

/**
 * Anmeldung wie bei Twitch auf dem Fernseher: Code auf twitch.tv/activate eingeben (in einem anderen
 * Browser oder Gerät) – oder gleich hier in der App, der Code ist dann schon eingetragen.
 */
@Composable
fun TwitchLoginScreen(vm: TwitchLoginViewModel = viewModel()) {
    val nav = LocalNav.current
    val context = LocalContext.current
    LaunchedEffect(vm.done) { if (vm.done) nav.back() }
    val c = vm.code

    Column(Modifier.fillMaxSize()) {
        BackTopBar("Bei Twitch anmelden")
        if (vm.inApp && c != null) {
            Text(
                "Melde dich bei Twitch an und tippe auf „Aktivieren“ – der Code ${c.userCode} ist schon eingetragen.",
                color = Yt.colors.textSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            ActivateWebView(c.activateUrl, Modifier.fillMaxSize())
            return@Column
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Mit Twitch-Konto kannst du im Chat schreiben, deine gefolgten Kanäle sehen und Streams nur für Abonnenten schauen. Werbung blendet GX Tube trotzdem aus.",
                color = Yt.colors.textSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            when {
                vm.error != null -> {
                    Text(vm.error.orEmpty(), color = Yt.colors.text, fontSize = 16.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { vm.start() }, colors = ButtonDefaults.buttonColors(containerColor = TwitchPurple, contentColor = Color.White)) { Text("Neuen Code holen") }
                }
                c == null -> CircularProgressIndicator(color = TwitchPurple)
                else -> {
                    Button(
                        onClick = { vm.inApp = true },
                        colors = ButtonDefaults.buttonColors(containerColor = TwitchPurple, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Hier in der App anmelden", fontWeight = FontWeight.Medium) }
                    Spacer(Modifier.height(28.dp))
                    Text("Oder auf einem anderen Gerät (PC, Tablet):", color = Yt.colors.textSecondary, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("twitch.tv/activate öffnen und diesen Code eingeben:", color = Yt.colors.text, fontSize = 15.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        c.userCode,
                        color = Yt.colors.text,
                        fontSize = 38.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 4.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Yt.colors.chip)
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { copy(context, c.userCode) }) { Text("Code kopieren") }
                        OutlinedButton(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.activateUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }) { Text("Im Browser") }
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = TwitchPurple, strokeWidth = 2.dp, modifier = Modifier.height(16.dp))
                        Spacer(Modifier.padding(4.dp))
                        Text("Warte auf die Bestätigung …", color = Yt.colors.textSecondary, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "Twitch zeigt dabei „Twitch auf dem Fernseher“ o. Ä. – das ist richtig so: GX Tube meldet sich wie ein Fernseher an. Dein Passwort bekommt GX Tube nie, die Anmeldung bleibt nur auf diesem Handy.",
                        color = Yt.colors.textSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Twitch-Code", text))
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ActivateWebView(url: String, modifier: Modifier) {
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    // Nur Twitch in der App, alles andere (Hilfe, Datenschutz …) im Browser
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val host = request.url.host.orEmpty()
                        if (host == "twitch.tv" || host.endsWith(".twitch.tv") || host.endsWith(".ttvnw.net")) return false
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        return true
                    }
                }
                loadUrl(url)
            }
        },
        onRelease = { it.destroy() },
        modifier = modifier,
    )
}
