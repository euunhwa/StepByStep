package com.example.easycar.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.easycar.R

internal val GuideCanvas = Color(0xfff3f4e9)
internal val GuidePaper = Color(0xfffffef8)
internal val GuideForest = Color(0xff173d31)
internal val GuideInk = Color(0xff19362d)
internal val GuideMuted = Color(0xff53675c)
internal val GuideLine = Color(0xffd9e2d4)
internal val GuideMint = Color(0xffd3efc8)
internal val GuideSelected = Color(0xffdcead6)
internal val GuideRoute = Color(0xffa6dc9a)
internal val GuideGreen = Color(0xff287653)
internal val GuideDrive = Color(0xff142e27)
internal val GuideDrivePanel = Color(0xff223c33)
internal val GuideDriveRaised = Color(0xff365448)
internal val GuideDriveMuted = Color(0xffbbcfc2)
internal val GuideWarning = Color(0xff80551e)
internal val GuideError = Color(0xffa43732)

// The cockpit and PiP share the same feedback contrast roles.
val Ink = GuideDrive
val Panel = GuideDrivePanel
val PanelLight = GuideDriveRaised
val Mint = GuideMint
val Muted = GuideDriveMuted
val White = GuidePaper
val Amber = Color(0xffffd28a)

internal val GuideReviewColors = lightColorScheme(
    primary = GuideForest, onPrimary = GuidePaper,
    primaryContainer = GuideMint, onPrimaryContainer = GuideInk,
    secondary = GuideGreen, onSecondary = GuidePaper,
    secondaryContainer = GuideSelected, onSecondaryContainer = GuideInk,
    background = GuideCanvas, onBackground = GuideInk,
    surface = GuidePaper, onSurface = GuideInk,
    surfaceVariant = GuideSelected, onSurfaceVariant = GuideMuted,
    surfaceContainer = GuidePaper, surfaceContainerHigh = GuidePaper,
    outline = GuideMuted, outlineVariant = GuideLine, error = GuideError,
)

internal val GuideDriveColors = darkColorScheme(
    primary = GuideMint, onPrimary = GuideDrive,
    primaryContainer = GuideDriveRaised, onPrimaryContainer = GuideMint,
    secondary = GuideRoute, onSecondary = GuideDrive,
    secondaryContainer = GuideDriveRaised, onSecondaryContainer = GuidePaper,
    background = GuideDrive, onBackground = GuidePaper,
    surface = GuideDrivePanel, onSurface = GuidePaper,
    surfaceVariant = GuideDriveRaised, onSurfaceVariant = GuideDriveMuted,
    surfaceContainer = GuideDrivePanel, surfaceContainerHigh = GuideDrivePanel,
    outline = GuideDriveMuted, outlineVariant = GuideDriveRaised,
)

@Composable internal fun GuideMark(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.ic_chageunchageun), contentDescription = null, modifier = modifier.size(40.dp))
}
