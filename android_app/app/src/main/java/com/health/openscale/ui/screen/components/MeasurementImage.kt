/*
 * openScale
 * Copyright (C) 2025 olie.xdev <olie.xdeveloper@googlemail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.ui.components

import android.graphics.ImageDecoder
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.filled.FileDownload
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.widget.Toast
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.health.openscale.R
import com.health.openscale.core.data.MeasurementType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val imageCache = object : LruCache<String, ImageBitmap>(16 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

/** Decodes [file] so that its shorter side is at most [maxPx] (thumbnails crop, so that side matters). */
private suspend fun loadImage(file: File, maxPx: Int, fitLongSide: Boolean = false): ImageBitmap? =
    withContext(Dispatchers.IO) {
        val key = "${file.path}@$maxPx@$fitLongSide"
        imageCache.get(key) ?: runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val side = if (fitLongSide) maxOf(w, h) else minOf(w, h)
                if (side > maxPx) {
                    val scale = maxPx.toFloat() / side
                    decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                }
            }.asImageBitmap()
        }.getOrNull()?.also { imageCache.put(key, it) }
    }

private sealed interface ImageState {
    data object Loading : ImageState
    data object Failed : ImageState
    data class Loaded(val bitmap: ImageBitmap) : ImageState
}

@Composable
private fun rememberImageState(file: File?, maxPx: Int, fitLongSide: Boolean = false): ImageState {
    val state by produceState<ImageState>(ImageState.Loading, file, maxPx, fitLongSide) {
        value = file?.let { loadImage(it, maxPx, fitLongSide) }?.let { ImageState.Loaded(it) } ?: ImageState.Failed
    }
    return state
}

/**
 * Rounded, center-cropped preview of a measurement photo, sized by [modifier].
 *
 * @param file The stored image, or null if the name does not resolve to one.
 * @param contentDescription Accessibility description of the photo.
 * @param onClick Optional click action, typically opening [FullscreenImageViewer].
 */
@Composable
fun MeasurementImageThumbnail(
    file: File?,
    contentDescription: String?,
    modifier: Modifier = Modifier.size(40.dp),
    onClick: (() -> Unit)? = null,
) {
    // Sized via onSizeChanged rather than BoxWithConstraints: rows measured with IntrinsicSize
    // (overview, table) would crash on a SubcomposeLayout.
    var maxPx by remember { mutableIntStateOf(0) }
    Box(
        modifier = modifier
            .onSizeChanged { maxPx = maxOf(it.width, it.height) }
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        val state = if (maxPx > 0) rememberImageState(file, maxPx) else ImageState.Loading
        ImageStateContent(state, contentDescription, ContentScale.Crop)
    }
}

@Composable
private fun ImageStateContent(state: ImageState, contentDescription: String?, contentScale: ContentScale) {
    Crossfade(targetState = state, label = "measurementImage") { current ->
        when (current) {
            is ImageState.Loaded -> Image(
                bitmap = current.bitmap,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize()
            )
            ImageState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ImageState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.BrokenImage,
                    contentDescription = contentDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Full-screen, zoomable view of a measurement photo on a black backdrop.
 *
 * Pinch zooms and pans, a double tap resets the zoom, and back or the close button dismisses.
 */
@Composable
fun FullscreenImageViewer(
    file: File?,
    title: String,
    onDismiss: () -> Unit,
    type: MeasurementType? = null,
) {
    val exportImage = rememberImageExporter()
    ImageViewerDialog(
        title = title,
        type = type,
        onDismiss = onDismiss,
        actions = {
            if (file != null) ExportImageButton(onClick = { exportImage(file, title) })
        },
    ) { zoom ->
        ZoomableImage(file = file, contentDescription = title, zoom = zoom, modifier = Modifier.fillMaxSize())
    }
}

/** One side of [FullscreenImageComparisonViewer]: the photo and the label that tells the sides apart. */
data class ComparedImage(val file: File?, val label: String)

/**
 * Two photos at full size for a before/after comparison, each labelled (e.g. with its date).
 *
 * They sit side by side when the screen is wider than tall (landscape, tablets) and stacked
 * otherwise, so each gets the larger share of the space. Zoom and pan act on both at once, which
 * keeps the same region in view on either side.
 */
@Composable
fun FullscreenImageComparisonViewer(
    first: ComparedImage,
    second: ComparedImage,
    title: String,
    onDismiss: () -> Unit,
    type: MeasurementType? = null,
) {
    val exportImage = rememberImageExporter()
    val onExport = { image: ComparedImage -> image.file?.let { exportImage(it, "$title ${image.label}") } ?: Unit }
    ImageViewerDialog(title = title, type = type, onDismiss = onDismiss) { zoom ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val paneModifier = Modifier.padding(2.dp)
            if (maxWidth > maxHeight) {
                Row(Modifier.fillMaxSize()) {
                    ComparedImagePane(first, zoom, onExport, paneModifier.weight(1f).fillMaxHeight())
                    ComparedImagePane(second, zoom, onExport, paneModifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    ComparedImagePane(first, zoom, onExport, paneModifier.weight(1f).fillMaxWidth())
                    ComparedImagePane(second, zoom, onExport, paneModifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun ComparedImagePane(
    image: ComparedImage,
    zoom: ZoomState,
    onExport: (ComparedImage) -> Unit,
    modifier: Modifier,
) {
    Box(modifier.clipToBounds()) {
        ZoomableImage(file = image.file, contentDescription = image.label, zoom = zoom, modifier = Modifier.fillMaxSize())
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp)
                .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.small)
                .padding(start = 8.dp)
        ) {
            Text(
                text = image.label,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
            if (image.file != null) ExportImageButton(onClick = { onExport(image) })
        }
    }
}

@Composable
private fun ExportImageButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = Icons.Filled.FileDownload,
            contentDescription = stringResource(R.string.content_desc_export_photo),
            tint = Color.White
        )
    }
}

/**
 * Lets the user export a copy of a stored photo wherever they choose (system "save as" dialog),
 * since the originals live in app-private storage. Returns `export(file, name)`.
 */
@Composable
private fun rememberImageExporter(): (File, String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<File?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        val source = pending
        pending = null
        if (uri == null || source == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                        ?: error("No output stream for $uri")
                }.isSuccess
            }
            Toast.makeText(
                context,
                if (saved) R.string.success_photo_exported else R.string.error_exporting_image,
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    return { file, name ->
        pending = file
        launcher.launch("openScale_" + name.replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_') + ".jpg")
    }
}

private class ZoomState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }
}

/** Dialog chrome shared by the viewers: black backdrop, top bar, and zoom gestures on the content. */
@Composable
private fun ImageViewerDialog(
    title: String,
    type: MeasurementType?,
    onDismiss: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable (ZoomState) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        // The dialog gets its own window; keep the status bar icons light on the black backdrop.
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            }
        }

        val zoom = remember { ZoomState() }
        val transformState = rememberTransformableState { zoomChange, panChange, _ ->
            zoom.scale = (zoom.scale * zoomChange).coerceIn(1f, 5f)
            zoom.offset = if (zoom.scale == 1f) Offset.Zero else zoom.offset + panChange
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.content_desc_close_photo),
                        tint = Color.White
                    )
                }
                if (type != null) {
                    RoundMeasurementIcon(
                        icon = type.icon.resource,
                        backgroundTint = Color(type.color),
                        size = 16.dp,
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    text = title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                actions()
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .clipToBounds()
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { zoom.reset() }) }
                    .transformable(transformState)
            ) {
                content(zoom)
            }
        }
    }
}

@Composable
private fun ZoomableImage(file: File?, contentDescription: String, zoom: ZoomState, modifier: Modifier) {
    val windowSize = LocalWindowInfo.current.containerSize
    val state = rememberImageState(file, maxOf(windowSize.width, windowSize.height), fitLongSide = true)
    Box(modifier, contentAlignment = Alignment.Center) {
        when (state) {
            is ImageState.Loaded -> Image(
                bitmap = state.bitmap,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom.scale
                        scaleY = zoom.scale
                        translationX = zoom.offset.x
                        translationY = zoom.offset.y
                    }
            )
            ImageState.Loading -> CircularProgressIndicator(color = Color.White)
            ImageState.Failed -> Icon(
                imageVector = Icons.Filled.BrokenImage,
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(48.dp)
            )
        }
    }
}
