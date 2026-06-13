package com.candidstickers

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.candidstickers.data.CandidCrop
import com.candidstickers.export.ShareSticker
import com.candidstickers.ui.CropCell
import com.candidstickers.ui.CropDetailDialog
import com.candidstickers.ui.MinerViewModel
import com.candidstickers.ui.PackNameDialog
import com.candidstickers.ui.PacksScreen
import com.candidstickers.ui.PacksViewModel
import com.candidstickers.ui.PeopleScreen
import com.candidstickers.ui.PeopleViewModel
import com.candidstickers.ui.SearchViewModel
import com.candidstickers.ui.SelectionBar
import com.candidstickers.ui.SelectionState
import com.candidstickers.work.ScanWorker

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
private const val TAB_PEOPLE = 2

@Composable
private fun App(
    minerVm: MinerViewModel = viewModel(),
    packsVm: PacksViewModel = viewModel(),
    peopleVm: PeopleViewModel = viewModel(),
    searchVm: SearchViewModel = viewModel(),
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

    var tab by rememberSaveable { mutableIntStateOf(TAB_STICKERS) }
    var selection by remember { mutableStateOf(SelectionState()) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Person names feed the Stickers detail dialog too, so load them up front.
    LaunchedEffect(Unit) { peopleVm.refresh() }
    // Re-query on tab switch: enrichment workers may have added tags/embeddings/people.
    LaunchedEffect(tab) {
        when (tab) {
            TAB_STICKERS -> {
                minerVm.loadExisting()
                searchVm.refresh()
            }
            TAB_PEOPLE -> peopleVm.refresh()
        }
    }

    val message = packsVm.message
    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            packsVm.consumeMessage()
        }
    }

    val onCreatePack: (String) -> Unit = { name ->
        packsVm.createPack(name, selection.ids.toList()) {
            selection = SelectionState()
            tab = TAB_PACKS
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
                NavigationBarItem(
                    selected = tab == TAB_PEOPLE,
                    onClick = { tab = TAB_PEOPLE },
                    icon = { Icon(Icons.Default.Person, contentDescription = null) },
                    label = { Text("People") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                TAB_STICKERS -> StickersTab(
                    vm = minerVm,
                    searchVm = searchVm,
                    selection = selection,
                    personName = { personId -> peopleVm.nameFor(personId) },
                    onSelectionChange = { selection = it },
                    onCreatePack = onCreatePack,
                )
                TAB_PACKS -> PacksScreen(packsVm)
                TAB_PEOPLE -> PeopleScreen(
                    vm = peopleVm,
                    selection = selection,
                    onSelectionChange = { selection = it },
                    onCreatePack = onCreatePack,
                )
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
    searchVm: SearchViewModel,
    selection: SelectionState,
    personName: (Long?) -> String?,
    onSelectionChange: (SelectionState) -> Unit,
    onCreatePack: (String) -> Unit,
) {
    val context = LocalContext.current
    var detailCrop by remember { mutableStateOf<CandidCrop?>(null) }
    var showNameDialog by remember { mutableStateOf(false) }
    val searchState = searchVm.state

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
        val enriching = vm.enriching
        if (vm.scanning) {
            if (enriching != null) {
                if (enriching.total > 0) {
                    LinearProgressIndicator(
                        progress = { enriching.done.toFloat() / enriching.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    if (enriching.total > 0) "Tagging ${enriching.done}/${enriching.total} stickers…"
                    else "Tagging stickers…",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else if (progress != null) {
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

        OutlinedTextField(
            value = searchState.query,
            onValueChange = searchVm::onQueryChange,
            placeholder = { Text("Search \"crying laughing\"…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchState.active) {
                    IconButton(onClick = searchVm::clearQuery) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        if (searchState.clipMissing) {
            Text(
                "Search needs the CLIP models — run scripts/fetch-models.sh",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        val gridCrops = searchState.gridCrops(vm.crops)
        when {
            searchState.showNoMatches -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No matches.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            gridCrops.isEmpty() && !vm.scanning && !searchState.active -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No candids yet — hit the button.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(gridCrops, key = { it.id }) { crop ->
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
    }

    detailCrop?.let { crop ->
        CropDetailDialog(
            crop = crop,
            personName = personName(crop.personId),
            onShare = {
                ShareSticker.share(context, crop)
                detailCrop = null
            },
            onDismiss = { detailCrop = null },
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
