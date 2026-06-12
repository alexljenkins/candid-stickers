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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.candidstickers.data.CandidCrop
import com.candidstickers.ui.MinerViewModel
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

@Composable
private fun App(vm: MinerViewModel = viewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, photoPermission) == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }

    if (!granted) {
        PermissionScreen { launcher.launch(photoPermission) }
        return
    }

    LaunchedEffect(Unit) { vm.loadExisting() }
    MinerScreen(vm)
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
private fun MinerScreen(vm: MinerViewModel) {
    var selected by remember { mutableStateOf<CandidCrop?>(null) }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            Text(
                "Candid Stickers",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(vertical = 12.dp),
            )

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
                        CropCell(crop) { selected = crop }
                    }
                }
            }
        }
    }

    selected?.let { crop ->
        AlertDialog(
            onDismissRequest = { selected = null },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } },
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
}

@Composable
private fun CropCell(crop: CandidCrop, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
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
        }
        Text(
            "${crop.reason} · ${"%.0f".format(crop.score * 100)}%",
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}
