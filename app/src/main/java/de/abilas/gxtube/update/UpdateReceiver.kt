package de.abilas.gxtube.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import androidx.core.content.getSystemService
import de.abilas.gxtube.BuildConfig
import de.abilas.gxtube.MainActivity
import de.abilas.gxtube.R

/** Rückmeldungen des PackageInstallers und Hinweis nach erfolgreichem Update. */
class UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_INSTALL_STATUS -> onInstallStatus(context, intent)
            Intent.ACTION_MY_PACKAGE_REPLACED -> notifyUpdated(context)
        }
    }

    private fun onInstallStatus(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // App wird ersetzt und neu gestartet
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Updater.onInstallFailed(
                    message = "Installation fehlgeschlagen" + (message?.let { ": $it" } ?: " ($status)"),
                    aborted = status == PackageInstaller.STATUS_FAILURE_ABORTED,
                )
            }
        }
    }

    private fun notifyUpdated(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "App-Updates", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle("GX Tube aktualisiert")
            .setContentText("Version ${BuildConfig.VERSION_NAME} ist installiert – tippen zum Öffnen")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "de.abilas.gxtube.INSTALL_STATUS"
        private const val CHANNEL = "updates"
        private const val NOTIFICATION_ID = 4711
    }
}
