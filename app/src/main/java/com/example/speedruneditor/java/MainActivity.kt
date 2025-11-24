package com.example.speedruneditor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
            surface = Color(0xFF1E1E1E)
        ),
        content = content
    )
}

@Composable
fun MainScreen(viewModel: EditorViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    
    // Permission Launcher
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
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
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
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { VideoPreviewSection(viewModel, state) }
        item { TimelineControls(viewModel, state) }
        item { NavigationPad(viewModel) }
        item { LRTControls(viewModel, state) }
        item { AppearanceControls(viewModel, state) }
        item {
            Button(
                onClick = { viewModel.startRender(LocalContext.current) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                enabled = !state.isLoading
            ) {
                Text(if (state.renderProgress > 0) "Rendering... ${state.renderProgress}%" else "RENDER VIDEO")
            }
        }
        item { 
             Text(state.statusMessage, color = Color.Gray, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun VideoPreviewSection(viewModel: EditorViewModel, state: UiState) {
    val context = LocalContext.current
    
    Column {
        Text(
            "Frame: ${state.currentFrame} | Start: ${state.startFrame} | End: ${state.endFrame}",
            style = MaterialTheme.typography.labelSmall
        )
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black)
                .pointerInteropFilter {
                    // Touch Drag Logic for Timer
                    if (it.action == MotionEvent.ACTION_MOVE || it.action == MotionEvent.ACTION_DOWN) {
                        viewModel.updateTimerPositionX(it.x / 1000f /* Aproximado, ideal: viewWidth */, context) // Simplificado
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
    }
}

@Composable
fun TimelineControls(viewModel: EditorViewModel, state: UiState) {
    val context = LocalContext.current
    val maxFrames = (state.videoProperties?.duration ?: 1.0) * (state.videoProperties?.fps ?: 30.0)
    
    Column {
        Slider(
            value = state.currentFrame.toFloat(),
            onValueChange = { viewModel.updateCurrentFrame(it.toInt(), context) },
            valueRange = 0f..maxFrames.toFloat()
        )
        
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { viewModel.setStartFrame() }) { Text("Set Start") }
            Button(onClick = { viewModel.goToStartFrame(context) }, colors = ButtonDefaults.textButtonColors()) { Text("Go Start") }
            Button(onClick = { viewModel.goToEndFrame(context) }, colors = ButtonDefaults.textButtonColors()) { Text("Go End") }
            OutlinedButton(onClick = { viewModel.setEndFrame() }) { Text("Set End") }
        }
    }
}

@Composable
fun NavigationPad(viewModel: EditorViewModel) {
    val context = LocalContext.current
    val buttons = listOf(
        "-10s" to { viewModel.navigateSeconds(-10, context) },
        "-1s" to { viewModel.navigateSeconds(-1, context) },
        "+1s" to { viewModel.navigateSeconds(1, context) },
        "+10s" to { viewModel.navigateSeconds(10, context) },
        "-5f" to { viewModel.navigateFrames(-5, context) },
        "-1f" to { viewModel.navigateFrames(-1, context) },
        "+1f" to { viewModel.navigateFrames(1, context) },
        "+5f" to { viewModel.navigateFrames(5, context) }
    )

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text("Fine Navigation", style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(modifier = Modifier.height(8.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(120.dp)
            ) {
                items(buttons.size) { index ->
                    FilledTonalButton(
                        onClick = buttons[index].second,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(buttons[index].first, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun LRTControls(viewModel: EditorViewModel, state: UiState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
            Text("Load Removal Tool (LRT)", style = MaterialTheme.typography.titleMedium)
            
            // Mode Selector
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                FilterChip(
                    selected = state.timerMode == TimerMode.RTA,
                    onClick = { viewModel.setTimerMode(TimerMode.RTA) },
                    label = { Text("RTA Only") }
                )
                FilterChip(
                    selected = state.timerMode == TimerMode.LRT,
                    onClick = { viewModel.setTimerMode(TimerMode.LRT) },
                    label = { Text("LRT Only") }
                )
                FilterChip(
                    selected = state.timerMode == TimerMode.BOTH,
                    onClick = { viewModel.setTimerMode(TimerMode.BOTH) },
                    label = { Text("Both") }
                )
            }
            
            Divider()
            
            // Load Marking
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
                    Text(if (state.currentLoadStartFrame != null) "STOP LOAD" else "START LOAD")
                }
                
                Text("${state.loadSegments.size} segments")
                
                IconButton(onClick = { viewModel.clearLastLoad() }) {
                    Icon(Icons.Default.Remove, "Remove Last")
                }
            }
            if (state.currentLoadStartFrame != null) {
                Text("Recording Load... Navigate to end frame and click STOP.", color = Color.Red, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun AppearanceControls(viewModel: EditorViewModel, state: UiState) {
    val context = LocalContext.current
    var showColorPicker by remember { mutableStateOf(false) }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            
            Text("Size: ${state.timerSize}")
            Slider(
                value = state.timerSize.toFloat(),
                onValueChange = { viewModel.setTimerSize(it.toInt(), context) },
                valueRange = 20f..200f
            )
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Color:")
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(state.timerColor))
                        .border(1.dp, Color.White, RoundedCornerShape(4.dp))
                        .clickable { showColorPicker = !showColorPicker }
                )
            }
            
            if (showColorPicker) {
                val controller = rememberColorPickerController()
                HsvColorPicker(
                    modifier = Modifier.fillMaxWidth().height(150.dp).padding(10.dp),
                    controller = controller,
                    onColorChanged = { envelope ->
                        viewModel.updateTimerColor(envelope.color.toArgb(), context)
                    }
                )
            }
        }
    }
}
