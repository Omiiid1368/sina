package ir.hezarzist

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Source figures remain bundled; magnification works without a viewer or network. */
@Composable
fun ZoomableQuestionImage(asset: String, snapshot: String = "bundled", description: String = "شکل علمی سؤال") {
    val context = LocalContext.current
    val preview = remember(asset, snapshot) { StudyAssets.decodeBitmap(context, asset, snapshot) }
    var expanded by remember(asset) { mutableStateOf(false) }
    if(preview == null) {
        Text("نمایش تصویر ممکن نشد.", color = MaterialTheme.colorScheme.error)
        return
    }
    Image(preview.asImageBitmap(), description,
        Modifier.fillMaxWidth().heightIn(max = 320.dp).clickable { expanded = true }, contentScale = ContentScale.Fit)
    TextButton({ expanded = true }) { Text("بزرگ‌نمایی تصویر") }
    if(expanded) {
        val full = remember(asset, snapshot) { StudyAssets.decodeBitmap(context, asset, snapshot, 4096) ?: preview }
        var zoom by remember { mutableFloatStateOf(1f) }
        val width = (LocalConfiguration.current.screenWidthDp - 16).coerceAtLeast(200).dp * zoom
        Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton({ expanded = false }) { Text("بستن") }
                        TextButton({ zoom = (zoom - .5f).coerceAtLeast(1f) }, enabled = zoom > 1f) { Text("−") }
                        TextButton({ zoom = (zoom + .5f).coerceAtMost(6f) }, enabled = zoom < 6f) { Text("+") }
                        Text("${fa(zoom)}×", Modifier.padding(12.dp))
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                        Image(full.asImageBitmap(), description,
                            Modifier.width(width).height(width * (full.height.toFloat() / full.width)), contentScale = ContentScale.FillBounds)
                    }
                }
            }
        }
    }
}
