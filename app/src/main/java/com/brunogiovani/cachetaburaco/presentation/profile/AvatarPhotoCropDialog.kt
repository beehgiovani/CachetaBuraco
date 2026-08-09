package com.brunogiovani.cachetaburaco.presentation.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.brunogiovani.cachetaburaco.presentation.components.MenuColors
import com.brunogiovani.cachetaburaco.presentation.components.MenuFilledButton
import com.brunogiovani.cachetaburaco.presentation.components.MenuShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

private val CropWindowSize = 260.dp
private const val MAX_ZOOM = 4f
private const val JPEG_QUALITY = 85
// ContentResolver.openInputStream pode travar indefinidamente sem lancar
// excecao em alguns provedores de midia (achado testando em emulador) --
// sem isso o dialogo fica com spinner eterno, sem jeito de desistir a nao
// ser fechando na mao.
private const val PHOTO_LOAD_TIMEOUT_MS = 12_000L

private sealed interface CropPhotoState {
    data object Loading : CropPhotoState
    data class Loaded(val bitmap: Bitmap) : CropPhotoState
    data object Failed : CropPhotoState
}

/** Recorte da foto de perfil direto no Compose -- sem lib de terceiro (o projeto
 * so usa google()/mavenCentral(), adicionar uma lib de crop exigiria JitPack). */
@Composable
fun AvatarPhotoCropDialog(
    imageUri: Uri,
    onConfirm: (ByteArray) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val graphicsLayer = rememberGraphicsLayer()
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var exporting by remember { mutableStateOf(false) }

    val photoState = produceState<CropPhotoState>(initialValue = CropPhotoState.Loading, imageUri) {
        value = CropPhotoState.Loading
        val loaded = withTimeoutOrNull(PHOTO_LOAD_TIMEOUT_MS) {
            withContext(Dispatchers.IO) { loadDownsampledBitmap(context, imageUri) }
        }
        value = loaded?.let { CropPhotoState.Loaded(it) } ?: CropPhotoState.Failed
    }
    val bitmap = (photoState.value as? CropPhotoState.Loaded)?.bitmap
    val loadFailed = photoState.value is CropPhotoState.Failed

    Dialog(onDismissRequest = { if (!exporting) onDismiss() }) {
        Surface(color = MenuColors.Ink, shape = MenuShapes.Card) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "Ajustar foto",
                    color = MenuColors.OnDark,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Text(
                    "Arraste e belisque pra ajustar. Só a área dentro do círculo vira sua foto.",
                    color = MenuColors.OnDarkMuted,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )

                Box(
                    modifier = Modifier
                        .size(CropWindowSize)
                        .clip(CircleShape)
                        .background(Color.Black)
                        .pointerInput(bitmap) {
                            if (bitmap == null) return@pointerInput
                            detectTransformGestures { _, pan, zoom, _ ->
                                val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                                val maxPanPx = (newScale - 1f) * size.width / 2f
                                offset = Offset(
                                    x = (offset.x + pan.x).coerceIn(-maxPanPx, maxPanPx),
                                    y = (offset.y + pan.y).coerceIn(-maxPanPx, maxPanPx)
                                )
                                scale = newScale
                            }
                        }
                        .drawWithContent {
                            graphicsLayer.record { this@drawWithContent.drawContent() }
                            drawLayer(graphicsLayer)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .matchParentSize()
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offset.x,
                                    translationY = offset.y
                                )
                        )
                    } else if (loadFailed) {
                        Text(
                            "Não deu pra abrir essa foto. Tente escolher outra.",
                            color = MenuColors.OnDarkMuted,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp)
                        )
                    } else {
                        CircularProgressIndicator(color = MenuColors.Gold, modifier = Modifier.size(28.dp))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MenuFilledButton(
                        text = "Cancelar",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        containerColor = MenuColors.OnDarkMuted.copy(alpha = 0.24f),
                        enabled = !exporting
                    )
                    MenuFilledButton(
                        text = "Confirmar",
                        onClick = {
                            if (bitmap == null || exporting) return@MenuFilledButton
                            exporting = true
                            scope.launch {
                                val jpegBytes = exportCroppedJpeg(graphicsLayer)
                                exporting = false
                                onConfirm(jpegBytes)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        containerColor = MenuColors.TableGreenLight,
                        loading = exporting,
                        enabled = bitmap != null && !exporting
                    )
                }
            }
        }
    }
}

private suspend fun exportCroppedJpeg(layer: GraphicsLayer): ByteArray {
    val exported = layer.toImageBitmap().asAndroidBitmap()
    val stream = ByteArrayOutputStream()
    exported.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
    return stream.toByteArray()
}

/** Evita estourar memoria com fotos de camera de dezenas de MP -- reduz pro
 * lado maior <= 2048px antes de carregar o bitmap de verdade. */
private fun loadDownsampledBitmap(context: Context, uri: Uri, maxDimension: Int = 2048): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    } ?: return null

    var sampleSize = 1
    while (bounds.outWidth / sampleSize > maxDimension || bounds.outHeight / sampleSize > maxDimension) {
        sampleSize *= 2
    }

    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    }
}
