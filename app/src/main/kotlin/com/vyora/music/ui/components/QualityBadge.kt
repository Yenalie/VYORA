package com.vyora.music.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vyora.music.R
import com.vyora.music.data.local.AudioFormatInfo
import com.vyora.music.data.local.AudioQuality
import com.vyora.music.data.local.AudioTier
import com.vyora.music.ui.design.glassClickable
import com.vyora.music.ui.theme.VyoraGroupedCell
import com.vyora.music.ui.theme.VyoraText
import com.vyora.music.ui.theme.VyoraTextMuted

@Composable
fun tierLabel(tier: AudioTier): String = stringResource(
    when (tier) {
        AudioTier.HI_RES_LOSSLESS -> R.string.quality_hires_lossless
        AudioTier.LOSSLESS -> R.string.quality_lossless
        AudioTier.LOSSY -> R.string.quality_lossy
    }
)

/**
 * The small outlined "Lossless" / "Hi-Res Lossless" mark, in the spirit of Apple Music's badge.
 * Renders nothing for lossy audio unless [lossyLabel] is given.
 */
@Composable
fun QualityBadge(
    tier: AudioTier,
    modifier: Modifier = Modifier,
    color: Color = VyoraTextMuted,
    lossyLabel: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val text = if (tier == AudioTier.LOSSY) lossyLabel ?: return else tierLabel(tier)
    val shape = RoundedCornerShape(5.dp)
    Row(
        modifier
            .then(if (onClick != null) Modifier.glassClickable(pressedScale = 0.92f, onClick = onClick) else Modifier)
            .border(0.8.dp, color.copy(alpha = 0.7f), shape)
            .padding(horizontal = 5.dp, vertical = 1.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tier != AudioTier.LOSSY) {
            Icon(Icons.Default.GraphicEq, null, tint = color, modifier = Modifier.size(11.dp))
            Spacer(Modifier.width(3.dp))
        }
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
            maxLines = 1,
        )
    }
}

/** "What am I hearing?" — codec, resolution, bitrate and where the audio comes from. */
@Composable
fun AudioFormatDialog(format: AudioFormatInfo, isLocal: Boolean, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VyoraGroupedCell,
        titleContentColor = VyoraText,
        textContentColor = VyoraTextMuted,
        shape = RoundedCornerShape(26.dp),
        icon = { Icon(Icons.Default.GraphicEq, null, tint = VyoraText) },
        title = { Text(tierLabel(format.tier)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                FormatLine(stringResource(R.string.format_codec), format.codec)
                if (format.bitDepth > 0) FormatLine(stringResource(R.string.format_bit_depth), "${format.bitDepth}-bit")
                if (format.sampleRateHz > 0) FormatLine(stringResource(R.string.format_sample_rate), AudioQuality.kiloHertz(format.sampleRateHz))
                if (format.bitrate > 0) FormatLine(stringResource(R.string.format_bitrate), "${(format.bitrate + 500) / 1000} kbps")
                if (format.channels > 0) FormatLine(
                    stringResource(R.string.format_channels),
                    if (format.channels == 2) stringResource(R.string.format_stereo) else if (format.channels == 1) stringResource(R.string.format_mono) else format.channels.toString(),
                )
                FormatLine(stringResource(R.string.format_source), stringResource(if (isLocal) R.string.format_source_device else R.string.format_source_stream))
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(
                        when {
                            format.tier == AudioTier.HI_RES_LOSSLESS -> R.string.format_note_hires
                            format.isLossless -> R.string.format_note_lossless
                            else -> R.string.format_note_lossy
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done), color = VyoraText) } },
    )
}

@Composable
private fun FormatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = VyoraTextMuted)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = VyoraText)
    }
}
