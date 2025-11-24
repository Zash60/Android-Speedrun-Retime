package com.example.speedruneditor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer // IMPORT ADICIONADO AQUI
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.skydoves.colorpicker.compose.HsvColorPicker
import com.github.skydoves.colorpicker.compose.rememberColorPickerController

class MainActivity : ComponentActivity() {
    private val viewModel: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SpeedrunEditorTheme {
                MainScreen(viewModel)
            }
        }
    }
}

@Composable
fun SpeedrunEditorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFBB86FC),
            secondary = Color(0xFF03DAC5),
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E),
            onSurface = Color.White
        ),
        content = content
    )
}

@Composable
fun MainScreen(viewModel: EditorViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    
    LaunchedEffect(Unit) {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(perm)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            if (state.screenState == ScreenState.INITIAL) {
                InitialScreen(viewModel)
            } else {
                EditorScreen(viewModel, state)
            }
            
            if (state.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(state.statusMessage, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun InitialScreen(viewModel: EditorViewModel) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.loadVideoFromUri(it, context) }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(16.dp))
        Text("Speedrun Retime Tool", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = { picker.launch("video/*") }) {
            Text("Select Video")
        }
    }
}

@Composable
fun EditorScreen(viewModel: EditorViewModel, state: UiState) {
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { VideoPreviewSection(viewModel, state) }
        item { TimelineControls(viewModel, state) }
        item { NavigationPad(viewModel) }
        item { LRTControls(viewModel, state) }
        item { AppearanceControls(viewModel, state) }
        item {
            Button(
                onClick = { viewModel.startRender(context) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                enabled = !state.isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text(if (state.renderProgress > 0) "Rendering... ${state.renderProgress}%" else "RENDER VIDEO", color = Color.Black)
            }
        }
        item { 
             Text(state.statusMessage, color = Color.Gray, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun VideoPreviewSection(viewModel: EditorViewModel, state: UiState) {
    val context = LocalContext.current
    
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Frame: ${state.currentFrame}", style = MaterialTheme.typography.labelSmall)
            Text("Time: ${formatPreviewTime(state)}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black)
                .pointerInteropFilter {
                    if (it.action == MotionEvent.ACTION_MOVE || it.action == MotionEvent.ACTION_DOWN) {
                        viewModel.updateTimerPositionX(it.x / 1000f, context)
                        viewModel.updateTimerPositionY(it.y / 600f, context) 
                    }
                    false
                }
        ) {
            state.currentFrameBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Start Frame: ${state.startFrame}", fontSize = 10.sp, color = Color.Gray)
            Text("End Frame: ${state.endFrame}", fontSize = 10.sp, color = Color.Gray)
        }
    }
}

fun formatPreviewTime(state: UiState): String {
    val fps = state.videoProperties?.fps ?: 30.0
    val seconds = state.currentFrame / fps
    return String.format(java.util.Locale.US, "%.3fs", seconds)
}

@Composable
fun TimelineControls(viewModel: EditorViewModel, state: UiState) {
    val context = LocalContext.current
    val maxFrames = (state.videoProperties?.duration ?: 1.0) * (state.videoProperties?.fps ?: 30.0)
    
    Column {
        Slider(
            value = state.currentFrame.toFloat(),
            onValueChange = { viewModel.updateCurrentFrame(it.toInt(), context) },
            valueRange = 0f..maxFrames.toFloat(),
            modifier = Modifier.height(20.dp)
        )
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp), 
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Button(
                onClick = { viewModel.setStartFrame() }, 
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(0.dp),
                shape = RoundedCornerShape(4.dp)
            ) { Text("Set Start", fontSize = 10.sp) }
            
            OutlinedButton(
                onClick = { viewModel.goToStartFrame(context) }, 
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(0.dp),
                shape = RoundedCornerShape(4.dp)
            ) { Text("Go Start", fontSize = 10.sp) }
            
            OutlinedButton(
                onClick = { viewModel.goToEndFrame(context) }, 
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(0.dp),
                shape = RoundedCornerShape(4.dp)
            ) { Text("Go End", fontSize = 10.sp) }
            
            Button(
                onClick = { viewModel.setEndFrame() }, 
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(0.dp),
                shape = RoundedCornerShape(4.dp)
            ) { Text("Set End", fontSize = 10.sp) }
        }
    }
}

@Composable
fun NavigationPad(viewModel: EditorViewModel) {
    val context = LocalContext.current

    @Composable
    fun NavRow(label: String, onMinus10: ()->Unit, onMinus5: ()->Unit, onMinus1: ()->Unit, onPlus1: ()->Unit, onPlus5: ()->Unit, onPlus10: ()->Unit) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(label, fontSize = 10.sp, color = Color.Gray, modifier = Modifier.align(Alignment.CenterHorizontally))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                SmallNavBtn("-10") { onMinus10() }
                SmallNavBtn("-5") { onMinus5() }
                SmallNavBtn("-1") { onMinus1() }
                Box(modifier = Modifier.width(1.dp).height(20.dp).background(Color.Gray).align(Alignment.CenterVertically))
                SmallNavBtn("+1") { onPlus1() }
                SmallNavBtn("+5") { onPlus5() }
                SmallNavBtn("+10") { onPlus10() }
            }
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Fine Navigation", style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
            
            NavRow("Frames",
                { viewModel.navigateFrames(-10, context) }, { viewModel.navigateFrames(-5, context) }, { viewModel.navigateFrames(-1, context) },
                { viewModel.navigateFrames(1, context) }, { viewModel.navigateFrames(5, context) }, { viewModel.navigateFrames(10, context) }
            )
            Divider(color = Color.DarkGray)
            NavRow("Seconds",
                { viewModel.navigateSeconds(-10, context) }, { viewModel.navigateSeconds(-5, context) }, { viewModel.navigateSeconds(-1, context) },
                { viewModel.navigateSeconds(1, context) }, { viewModel.navigateSeconds(5, context) }, { viewModel.navigateSeconds(10, context) }
            )
            Divider(color = Color.DarkGray)
            NavRow("Minutes",
                { viewModel.navigateMinutes(-10, context) }, { viewModel.navigateMinutes(-5, context) }, { viewModel.navigateMinutes(-1, context) },
                { viewModel.navigateMinutes(1, context) }, { viewModel.navigateMinutes(5, context) }, { viewModel.navigateMinutes(10, context) }
            )
        }
    }
}

@Composable
fun SmallNavBtn(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.width(40.dp).height(35.dp)
    ) {
        Text(text, fontSize = 11.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LRTControls(viewModel: EditorViewModel, state: UiState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
            Text("Load Removal Tool (LRT)", style = MaterialTheme.typography.titleMedium)
            
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                FilterChip(selected = state.timerMode == TimerMode.RTA, onClick = { viewModel.setTimerMode(TimerMode.RTA) }, label = { Text("RTA") })
                FilterChip(selected = state.timerMode == TimerMode.LRT, onClick = { viewModel.setTimerMode(TimerMode.LRT) }, label = { Text("LRT") })
                FilterChip(selected = state.timerMode == TimerMode.BOTH, onClick = { viewModel.setTimerMode(TimerMode.BOTH) }, label = { Text("Both") })
            }
            
            Divider()
            
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { viewModel.toggleLoadMark() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.currentLoadStartFrame != null) Color.Red else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(if (state.currentLoadStartFrame != null) "STOP LOADING" else "MARK LOADING")
                }
                
                Column(horizontalAlignment = Alignment.End) {
                    Text("${state.loadSegments.size} segments saved", fontSize = 12.sp)
                    TextButton(onClick = { viewModel.clearLastLoad() }) {
                        Icon(Icons.Default.Remove, "Remove", modifier = Modifier.size(16.dp))
                        Text("Undo Last", fontSize = 12.sp)
                    }
                }
            }
            
            if (state.currentLoadStartFrame != null) {
                Text("🔴 Recording Load... Navigate to where loading ends and click STOP.", color = Color.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun AppearanceControls(viewModel: EditorViewModel, state: UiState) {
    val context = LocalContext.current
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.loadCustomFont(it, context) }
    }
    
    var showTimerColorPicker by remember { mutableStateOf(false) }
    var showOutlineColorPicker by remember { mutableStateOf(false) }
    var formatExpanded by remember { mutableStateOf(false) }
    
    val formats = listOf("MMSSmmm", "HHMMSSmmm", "SSmmm", "HHMMSS", "MMSS", "MMSScc")
    val formatLabels = listOf("MM:SS.mmm", "H:MM:SS.mmm", "S.mmm", "H:MM:SS", "MM:SS", "MM:SS.cc")

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Timer Appearance", style = MaterialTheme.typography.titleMedium)
            
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text("Format:", modifier = Modifier.weight(1f))
                Box {
                    Button(onClick = { formatExpanded = true }) {
                        Text(formatLabels.getOrElse(formats.indexOf(state.timerFormat)) { state.timerFormat })
                    }
                    DropdownMenu(expanded = formatExpanded, onDismissRequest = { formatExpanded = false }) {
                        formats.forEachIndexed { index, fmt ->
                            DropdownMenuItem(
                                text = { Text(formatLabels[index]) },
                                onClick = { 
                                    viewModel.updateTimerFormat(fmt, context)
                                    formatExpanded = false 
                                }
                            )
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("Font:", modifier = Modifier.weight(1f))
                if (state.customFontName != null) {
                    Text(state.customFontName, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
                }
                OutlinedButton(onClick = { fontPicker.launch("*/*") }) {
                    Text("Import .ttf")
                }
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Text Style", fontSize = 12.sp, color = Color.Gray)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Size", fontSize = 12.sp, modifier = Modifier.width(40.dp))
                Slider(
                    value = state.timerSize.toFloat(),
                    onValueChange = { viewModel.setTimerSize(it.toInt(), context) },
                    valueRange = 20f..200f,
                    modifier = Modifier.weight(1f)
                )
                ColorPreviewBox(state.timerColor) { showTimerColorPicker = true }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Outline", fontSize = 12.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = state.outlineEnabled,
                    onCheckedChange = { viewModel.toggleOutline(it, context) },
                    modifier = Modifier.scale(0.8f)
                )
            }
            
            if (state.outlineEnabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Width", fontSize = 12.sp, modifier = Modifier.width(40.dp))
                    Slider(
                        value = state.outlineWidth.toFloat(),
                        onValueChange = { viewModel.setOutlineWidth(it.toInt()) },
                        onValueChangeFinished = { viewModel.refreshPreview(context) }, 
                        valueRange = 0f..20f,
                        modifier = Modifier.weight(1f)
                    )
                    ColorPreviewBox(state.outlineColor) { showOutlineColorPicker = true }
                }
            }
            
            if (showTimerColorPicker) {
                ColorPickerDialog(
                    initialColor = state.timerColor,
                    onColorSelected = { viewModel.updateTimerColor(it, context) },
                    onDismiss = { showTimerColorPicker = false }
                )
            }
            if (showOutlineColorPicker) {
                ColorPickerDialog(
                    initialColor = state.outlineColor,
                    onColorSelected = { viewModel.updateOutlineColor(it, context) },
                    onDismiss = { showOutlineColorPicker = false }
                )
            }
        }
    }
}

@Composable
fun ColorPreviewBox(color: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(start = 8.dp)
            .size(30.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(color))
            .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
            .clickable { onClick() }
    )
}

@Composable
fun ColorPickerDialog(initialColor: Int, onColorSelected: (Int) -> Unit, onDismiss: () -> Unit) {
    val controller = rememberColorPickerController()
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick a color") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                HsvColorPicker(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    controller = controller,
                    initialColor = Color(initialColor)
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onColorSelected(controller.selectedColor.value.toArgb())
                onDismiss()
            }) { Text("Select") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

fun Modifier.scale(scale: Float): Modifier = this.then(
    Modifier.graphicsLayer(scaleX = scale, scaleY = scale)
)
