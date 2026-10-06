package de.abilas.gxtube.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import de.abilas.gxtube.BuildConfig
import de.abilas.gxtube.data.Http
import de.abilas.gxtube.data.Library
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.io.File

/**
 * Updates direkt in der App: prüft die neueste Version auf GitHub (Releases), lädt die APK
 * und installiert sie über den PackageInstaller – ohne Umweg über den Browser.
 */
object Updater {
    private const val TAG = "Updater"
    private const val REPO = "abilas-sivarajah/adblock-tube-gx"
    private const val PREFS = "updater"
    private const val CHECK_INTERVAL_MS = 15 * 60 * 1000L
    private const val SNOOZE_MS = 12 * 60 * 60 * 1000L

    @Serializable
    private data class GhAsset(val name: String, val browser_download_url: String, val size: Long = 0)

    @Serializable
    private data class GhRelease(
        val tag_name: String,
        val name: String? = null,
        val body: String? = null,
        val assets: List<GhAsset> = emptyList(),
    )

    data class Release(val version: String, val apkUrl: String, val size: Long, val notes: String)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val progress: Float) : State
        data class Installing(val release: Release) : State
        data class Failed(val release: Release?, val message: String) : State
        data object UpToDate : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Installierte Version, z. B. "0.1.7" */
    val currentVersion: String get() = BuildConfig.VERSION_NAME.substringBefore('-')

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isNewer(remote: String, local: String = currentVersion): Boolean {
        fun parts(v: String) = v.trimStart('v', 'V').substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(remote)
        val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Beim Öffnen der App: höchstens alle 15 Minuten nachsehen; "Später" gilt 12 Stunden. */
    fun checkOnOpen(context: Context, scope: CoroutineScope) {
        if (!Library.settings.autoUpdateCheck) return
        val p = prefs(context)
        val now = System.currentTimeMillis()
        if (now - p.getLong("lastCheck", 0) < CHECK_INTERVAL_MS) return
        if (_state.value !is State.Idle && _state.value !is State.UpToDate) return
        p.edit().putLong("lastCheck", now).apply()
        scope.launch {
            val release = runCatching { fetchLatest() }
                .onFailure { Log.w(TAG, "Update-Prüfung fehlgeschlagen", it) }
                .getOrNull() ?: return@launch
            if (!isNewer(release.version)) return@launch
            val snoozed = p.getString("snoozedVersion", null) == release.version &&
                now - p.getLong("snoozedAt", 0) < SNOOZE_MS
            if (!snoozed) _state.value = State.Available(release)
        }
    }

    /** Knopf "Nach Updates suchen" in den Einstellungen. */
    suspend fun checkNow() {
        _state.value = State.Checking
        _state.value = try {
            val release = fetchLatest()
            if (isNewer(release.version)) State.Available(release) else State.UpToDate
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            State.Failed(null, "Update-Prüfung fehlgeschlagen: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    fun snooze(context: Context) {
        val release = (_state.value as? State.Available)?.release
        if (release != null) {
            prefs(context).edit()
                .putString("snoozedVersion", release.version)
                .putLong("snoozedAt", System.currentTimeMillis())
                .apply()
        }
        _state.value = State.Idle
    }

    fun dismiss() {
        _state.value = State.Idle
    }

    private suspend fun fetchLatest(): Release = withContext(Dispatchers.IO) {
        val request = okhttp3.Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "GXTube/${BuildConfig.VERSION_NAME}")
            .build()
        Http.client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("GitHub antwortet mit HTTP ${r.code}")
            val release = Library.json.decodeFromString<GhRelease>(r.body.string())
            val apk = release.assets.firstOrNull { it.name.endsWith(".apk") }
                ?: throw IllegalStateException("Keine APK im neuesten Release")
            val notes = release.body.orEmpty().lines()
                .firstOrNull { it.startsWith("Neu:") }
                ?.removePrefix("Neu:")?.trim()
                .orEmpty()
            Release(release.tag_name.trimStart('v'), apk.browser_download_url, apk.size, notes)
        }
    }

    // ------------------------------------------------------------ Installieren

    /** Darf GX Tube Apps installieren? (einmalig in den Android-Einstellungen erlauben) */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    suspend fun downloadAndInstall(context: Context, release: Release) {
        val app = context.applicationContext
        try {
            _state.value = State.Downloading(release, 0f)
            val file = download(app, release)
            _state.value = State.Installing(release)
            install(app, file)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Update fehlgeschlagen", t)
            _state.value = State.Failed(release, "Update fehlgeschlagen: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private suspend fun download(context: Context, release: Release): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "GXTube-${release.version}.apk")
        val request = okhttp3.Request.Builder().url(release.apkUrl).header("User-Agent", "GXTube").build()
        Http.client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("Download: HTTP ${r.code}")
            val total = r.body.contentLength().takeIf { it > 0 } ?: release.size
            r.body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (total > 0 && done - lastReport > 256 * 1024) {
                            lastReport = done
                            _state.value = State.Downloading(release, (done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        }
        file
    }

    private suspend fun install(context: Context, file: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= 31) {
            // Ab dem zweiten Update kann Android ohne Rückfrage installieren
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("gxtube.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION_INSTALL_STATUS)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    internal fun onInstallFailed(message: String, aborted: Boolean) {
        val release = when (val s = _state.value) {
            is State.Installing -> s.release
            is State.Downloading -> s.release
            is State.Available -> s.release
            else -> null
        }
        _state.value = when {
            aborted && release != null -> State.Available(release)
            else -> State.Failed(release, message)
        }
    }
}
