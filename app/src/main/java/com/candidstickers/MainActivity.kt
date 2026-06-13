package com.candidstickers

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.candidstickers.data.CandidCrop
import com.candidstickers.export.ShareSticker
import com.candidstickers.ui.MinerViewModel
import com.candidstickers.ui.PacksScreen
import com.candidstickers.ui.PacksViewModel
import com.candidstickers.ui.SelectionState
import com.candidstickers.work.ScanWorker
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScanWorker.schedule(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                App()
            }
        }
    }
}

private val photoPermission =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
    else Manifest.permission.READ_EXTERNAL_STORAGE

private const val TAB_STICKERS = 0
private const val TAB_PACKS = 1

@Composable
private fun App(
    minerVm: MinerViewModel = viewModel(),
    packsVm: PacksViewModel = viewModel(),
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, photoPermission) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }

    if (!granted) {
        PermissionScreen { permissionLauncher.launch(photoPermission) }
        return
    }

    LaunchedEffect(Unit) { minerVm.loadExisting() }

    var tab by rememberSaveable { mutableIntStateOf(TAB_STICKERS) }
    var selection by remember { mutableStateOf(SelectionState()) }
    val snackbarHostState = remember { SnackbarHostState() }

    val message = packsVm.message
    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            packsVm.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == TAB_STICKERS,
                    onClick = { tab = TAB_STICKERS },
                    icon = { Icon(Icons.Default.Face, contentDescription = null) },
                    label = { Text("Stickers") },
                )
                NavigationBarItem(
                    selected = tab == TAB_PACKS,
                    onClick = { tab = TAB_PACKS },
                    icon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                    label = { Text("Packs") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (tab == TAB_STICKERS) {
                StickersTab(
                    vm = minerVm,
                    selection = selection,
                    onSelectionChange = { selection = it },
                    onCreatePack = { name ->
                        packsVm.createPack(name, selection.ids.toList()) {
                            selection = SelectionState()
                            tab = TAB_PACKS
                        }
                    },
                )
            } else {
                PacksScreen(packsVm)
            }
        }
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Candid Stickers", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Mines your camera roll for the candid faces everyone else deletes — " +
                "eyes shut, mid-sneeze, gremlin mode — and turns them into stickers. " +
                "Everything stays on this phone.",
            modifier = Modifier.padding(vertical = 24.dp),
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRequest) { Text("Allow photo access") }
    }
}

@Composable
private fun StickersTab(
    vm: MinerViewModel,
    selection: SelectionState,
    onSelectionChange: (SelectionState) -> Unit,
    onCreatePack: (String) -> Unit,
) {
    val context = LocalContext.current
    var detailCrop by remember { mutableStateOf<CandidCrop?>(null) }
    var showNameDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (selection.active) {
            SelectionBar(
                selection = selection,
                onCancel = { onSelectionChange(selection.clear()) },
                onCreatePack = { showNameDialog = true },
            )
        } else {
            Text(
                "Candid Stickers",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }

        val progress = vm.progress
        if (vm.scanning) {
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.scanned.toFloat() / progress.total.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Scanned ${progress.scanned}/${progress.total} photos — ${progress.cropsFound} candids found",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    "Looking for photos…",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        } else {
            Button(
                onClick = vm::startScan,
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                Text(if (vm.crops.isEmpty()) "Mine my camera roll" else "Scan new photos")
            }
        }

        if (vm.crops.isEmpty() && !vm.scanning) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No candids yet — hit the button.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(vm.crops, key = { it.id }) { crop ->
                    CropCell(
                        crop = crop,
                        selected = selection.active && crop.id in selection.ids,
                        onClick = {
                            if (selection.active) onSelectionChange(selection.toggle(crop.id))
                            else detailCrop = crop
                        },
                        onLongClick = {
                            onSelectionChange(
                                if (selection.active) selection.toggle(crop.id)
                                else selection.start(crop.id)
                            )
                        },
                    )
                }
            }
        }
    }

    detailCrop?.let { crop ->
        AlertDialog(
            onDismissRequest = { detailCrop = null },
            confirmButton = {
                TextButton(onClick = {
                    ShareSticker.share(context, crop)
                    detailCrop = null
                }) { Text("Share") }
            },
            dismissButton = { TextButton(onClick = { detailCrop = null }) { Text("Close") } },
            title = { Text("${crop.reason} · ${"%.0f".format(crop.score * 100)}%") },
            text = {
                AsyncImage(
                    model = Uri.parse(crop.contentUri),
                    contentDescription = "Original photo",
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }

    if (showNameDialog) {
        PackNameDialog(
            onDismiss = { showNameDialog = false },
            onCreate = { name ->
                showNameDialog = false
                onCreatePack(name)
            },
        )
    }
}

@Composable
private fun SelectionBar(
    selection: SelectionState,
    onCancel: () -> Unit,
    onCreatePack: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        IconButton(onClick = onCancel) {
            Icon(Icons.Default.Close, contentDescription = "Cancel selection")
        }
        Text(
            "${selection.count} selected",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = onCreatePack, enabled = selection.canCreatePack) {
            Text("Create pack (${selection.count})")
        }
    }
}

@Composable
private fun PackNameDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("Candids") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pack name") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CropCell(
    crop: CandidCrop,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(Color(0xFF2A2A2E)),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = File(crop.cropPath),
                contentDescription = crop.reason,
                modifier = Modifier.fillMaxSize().padding(4.dp),
            )
            if (selected) {
                Box(Modifier.matchParentSize().background(Color(0x66000000)))
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                )
            }
        }
        Text(
            "${crop.reason} · ${"%.0f".format(crop.score * 100)}%",
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}
