package de.abilas.gxtube

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.crossfade
import de.abilas.gxtube.data.Account
import de.abilas.gxtube.data.Library
import de.abilas.gxtube.data.NewPipeDownloader
import de.abilas.gxtube.data.SubscriptionFeed
import de.abilas.gxtube.player.PlayerController
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class GxTubeApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        Library.init(this)
        Account.init(this)
        SubscriptionFeed.init(this)
        applyRegion()
        PlayerController.init(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .crossfade(true)
            .build()

    companion object {
        /** Sprache/Land für YouTube (Trends, Datumsangaben, Suchergebnisse). */
        fun applyRegion() {
            val s = Library.settings
            NewPipe.init(
                NewPipeDownloader.instance,
                Localization(s.language, s.country),
                ContentCountry(s.country),
            )
        }
    }
}
