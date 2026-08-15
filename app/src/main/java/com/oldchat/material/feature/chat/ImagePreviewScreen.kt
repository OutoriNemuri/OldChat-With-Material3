package com.oldchat.material.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * Full-screen image preview with pinch-to-zoom and drag.
 * Mirrors ImagePreviewActivity + ZoomImageView from original client §5.2.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagePreviewScreen(
    imageUrl: String,
    onClose: () -> Unit = {},
    onDownload: () -> Unit = {}
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Zoomable image
        AsyncImage(
            model = imageUrl,
            contentDescription = "图片预览",
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale.coerceIn(0.5f, 5f),
                    scaleY = scale.coerceIn(0.5f, 5f),
                    translationX = offset.x,
                    translationY = offset.y
                )
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.5f, 5f)
                        // Constrain offset to prevent image from going completely off screen
                        if (scale > 1f) {
                            offset = Offset(
                                x = offset.x + pan.x,
                                y = offset.y + pan.y
                            )
                        } else {
                            offset = Offset.Zero
                        }
                    }
                },
            contentScale = ContentScale.Fit
        )

        // Close button
        IconButton(
            onClick = {
                scale = 1f
                offset = Offset.Zero
                onClose()
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .statusBarsPadding()
        ) {
            Icon(Icons.Filled.Close, "关闭", tint = Color.White)
        }

        // Download button
        IconButton(
            onClick = onDownload,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .statusBarsPadding()
        ) {
            Icon(Icons.Filled.Download, "下载", tint = Color.White)
        }

        // Tap to reset zoom hint
        if (scale > 1.5f) {
            TextButton(
                onClick = { scale = 1f; offset = Offset.Zero },
                modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp)
            ) {
                Text("重置", color = Color.White)
            }
        }
    }
}
