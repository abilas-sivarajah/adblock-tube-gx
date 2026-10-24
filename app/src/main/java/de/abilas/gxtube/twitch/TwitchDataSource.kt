package de.abilas.gxtube.twitch

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Datenquelle für Twitch in ExoPlayer: Playlists (und das schwarze Ersatz-Stück) beantwortet der
 * Werbeblocker, die Videostücke kommen ganz normal aus dem Netz.
 */
@UnstableApi
class TwitchDataSource(private val upstream: DataSource, private val adBlock: TwitchAdBlock) : DataSource {
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val bytes = try {
            adBlock.handle(dataSpec.uri.toString())
        } catch (e: TwitchAdBlock.HttpError) {
            // z. B. 404: Kanal gerade offline
            throw HttpDataSource.InvalidResponseCodeException(e.code, null, e, emptyMap(), dataSpec, ByteArray(0))
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException(e, dataSpec, androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
        }
        val ds = if (bytes != null) ByteArrayDataSource(bytes) else upstream
        current = ds
        return ds.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current?.read(buffer, offset, length) ?: throw IOException("nicht geöffnet")

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        val ds = current
        current = null
        ds?.close()
    }

    class Factory(private val upstream: DataSource.Factory, private val adBlock: TwitchAdBlock) : DataSource.Factory {
        override fun createDataSource(): DataSource = TwitchDataSource(upstream.createDataSource(), adBlock)
    }
}
