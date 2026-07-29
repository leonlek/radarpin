package com.bydmapcam.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmapcam.media.MediaLink

/**
 * Transport controls for whatever else is playing, shown only while something actually is.
 * Big flat glyph buttons — this gets pressed with a thumb at 100 km/h.
 */
@Composable
fun MediaBar(
    nowPlaying: MediaLink.NowPlaying,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.widthIn(max = 320.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.large,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlyphButton("⏮", onPrevious)
            GlyphButton(if (nowPlaying.playing) "⏸" else "▶", onPlayPause)
            GlyphButton("⏭", onNext)
            nowPlaying.label?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp, end = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun GlyphButton(glyph: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Text(glyph, fontSize = 18.sp)
    }
}
