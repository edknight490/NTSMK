package com.mudita.mmd.components.slider

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SliderMMD(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
) {
    val activeColor = if (enabled) Color.Black else Color(0xFF999999)
    val inactiveColor = if (enabled) Color(0xFFE5E5E5) else Color(0xFFF2F2F2)
    val borderColor = if (enabled) Color.Black else Color(0xFFB3B3B3)
    val thumbBgColor = if (enabled) Color.Black else Color(0xFFCCCCCC)

    Slider(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier,
        thumb = {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .background(thumbBgColor, CircleShape)
                    .border(BorderStroke(2.dp, Color.White), CircleShape)
            )
        },
        track = { sliderState ->
            val fraction = sliderState.value / (sliderState.valueRange.endInclusive - sliderState.valueRange.start)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .background(inactiveColor, CircleShape)
                    .border(BorderStroke(1.5.dp, borderColor), CircleShape)
                    .clip(CircleShape)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = fraction)
                        .fillMaxHeight()
                        .background(activeColor)
                )
            }
        },
        colors = SliderDefaults.colors(
            thumbColor = activeColor,
            activeTrackColor = Color.Transparent,
            inactiveTrackColor = Color.Transparent,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
            disabledActiveTrackColor = Color.Transparent,
            disabledInactiveTrackColor = Color.Transparent
        )
    )
}
