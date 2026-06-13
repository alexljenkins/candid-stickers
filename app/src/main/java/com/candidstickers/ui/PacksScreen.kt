package com.candidstickers.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.candidstickers.data.PackRow
import com.candidstickers.data.PackStickerRow
import com.candidstickers.export.WhatsAppExport
import java.io.File

@Composable
fun PacksScreen(vm: PacksViewModel) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.refresh() }

    val whatsAppInstalled = remember { WhatsAppExport.isInstalled(context) }
    val whatsAppLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        vm.onWhatsAppResult(result.resultCode, result.data)
    }
    var packToDelete by remember { mutableStateOf<PackRow?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text(
            "Packs",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(vertical = 12.dp),
        )

        if (vm.packs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No packs yet — long-press a sticker on the Stickers tab to start one.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                items(vm.packs, key = { it.id }) { pack ->
                    PackCard(
                        pack = pack,
                        stickers = vm.stickers[pack.id].orEmpty(),
                        trayFile = vm.trayFile(pack),
                        expanded = vm.expandedPackId == pack.id,
                        whatsAppInstalled = whatsAppInstalled,
                        onToggleExpand = { vm.toggleExpanded(pack.id) },
                        onAddToWhatsApp = {
                            try {
                                whatsAppLauncher.launch(WhatsAppExport.addPackIntent(pack))
                            } catch (e: ActivityNotFoundException) {
                                vm.showMessage("WhatsApp not installed")
                            }
                        },
                        onDelete = { packToDelete = pack },
                        onRemoveSticker = { cropId -> vm.removeSticker(pack.id, cropId) },
                    )
                }
            }
        }
    }

    packToDelete?.let { pack ->
        AlertDialog(
            onDismissRequest = { packToDelete = null },
            title = { Text("Delete \"${pack.name}\"?") },
            text = {
                Text(
                    "Removes the pack and its sticker files from this app. " +
                        "If you already added it, WhatsApp keeps its own copy."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deletePack(pack.id)
                    packToDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { packToDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PackCard(
    pack: PackRow,
    stickers: List<PackStickerRow>,
    trayFile: File,
    expanded: Boolean,
    whatsAppInstalled: Boolean,
    onToggleExpand: () -> Unit,
    onAddToWhatsApp: () -> Unit,
    onDelete: () -> Unit,
    onRemoveSticker: (Long) -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onToggleExpand)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = trayFile,
                    contentDescription = "${pack.name} tray icon",
                    modifier = Modifier.size(48.dp).background(Color(0xFF2A2A2E)),
                )
                Column(Modifier.padding(start = 12.dp)) {
                    Text(pack.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (pack.stickerCount == 1) "1 sticker" else "${pack.stickerCount} stickers",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (!expanded && stickers.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    stickers.take(6).forEach { sticker ->
                        AsyncImage(
                            model = File(sticker.cropPath),
                            contentDescription = null,
                            modifier = Modifier.size(40.dp).background(Color(0xFF2A2A2E)),
                        )
                    }
                }
            }

            if (expanded) {
                stickers.chunked(4).forEach { row ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        row.forEach { sticker ->
                            PackStickerCell(
                                sticker = sticker,
                                onLongClick = { onRemoveSticker(sticker.cropId) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Text(
                    "Long-press a sticker to remove it from the pack.",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Button(onClick = onAddToWhatsApp, enabled = whatsAppInstalled) {
                    Text(if (whatsAppInstalled) "Add to WhatsApp" else "WhatsApp not installed")
                }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PackStickerCell(
    sticker: PackStickerRow,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .aspectRatio(1f)
            .background(Color(0xFF2A2A2E))
            .combinedClickable(onClick = {}, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = File(sticker.cropPath),
            contentDescription = "Sticker ${sticker.fileName}",
            modifier = Modifier.fillMaxSize().padding(4.dp),
        )
    }
}
