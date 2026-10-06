package de.abilas.gxtube.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.abilas.gxtube.R
import de.abilas.gxtube.ui.LocalNav
import de.abilas.gxtube.ui.theme.Yt
import kotlin.math.roundToInt

/** Kopfzeile mit Logo und Suche – wie in der YouTube-App. */
@Composable
fun AppTopBar(actions: @Composable RowScope.() -> Unit = {}) {
    val nav = LocalNav.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.height(24.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            "GX Tube",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.8).sp,
            color = Yt.colors.text,
        )
        Spacer(Modifier.weight(1f))
        actions()
        IconButton(onClick = { nav.openSearch() }) {
            Icon(Icons.Outlined.Search, contentDescription = "Suchen", tint = Yt.colors.text)
        }
    }
}

/** Kopfzeile mit Zurück-Pfeil. */
@Composable
fun BackTopBar(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    val nav = LocalNav.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(Yt.colors.background)
            .statusBarsPadding()
            .height(52.dp)
            .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { nav.back() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück", tint = Yt.colors.text)
        }
        Text(
            title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = Yt.colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

/**
 * Kopfzeile, die beim Runterscrollen verschwindet und beim Hochscrollen sofort
 * wiederkommt (wie in der YouTube-App).
 */
@Composable
fun CollapsingHeaderLayout(
    headerHeight: Dp,
    header: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val headerPx = with(density) { headerHeight.toPx() }
    var offset by remember { mutableFloatStateOf(0f) }
    val connection = remember(headerPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                offset = (offset + available.y).coerceIn(-headerPx, 0f)
                return Offset.Zero
            }
        }
    }
    val bg = Yt.colors.background
    Box(
        Modifier
            .fillMaxSize()
            .nestedScroll(connection),
    ) {
        content(PaddingValues(top = statusTop + headerHeight))
        Column(
            Modifier
                .offset { IntOffset(0, offset.roundToInt()) }
                .padding(top = statusTop)
                .fillMaxWidth()
                .height(headerHeight)
                .background(bg),
        ) { header() }
        Box(
            Modifier
                .fillMaxWidth()
                .height(statusTop)
                .background(bg),
        )
    }
}
