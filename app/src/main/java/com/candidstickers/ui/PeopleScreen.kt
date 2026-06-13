package com.candidstickers.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.candidstickers.data.CandidCrop
import com.candidstickers.data.PersonRow
import com.candidstickers.export.ShareSticker
import java.io.File

/**
 * People tab: grid of person cards; tapping one opens that person's crops grid,
 * which shares the multi-select pack flow with the Stickers tab.
 */
@Composable
fun PeopleScreen(
    vm: PeopleViewModel,
    selection: SelectionState,
    onSelectionChange: (SelectionState) -> Unit,
    onCreatePack: (String) -> Unit,
) {
    LaunchedEffect(Unit) { vm.refresh() }

    val open = vm.openPerson
    BackHandler(enabled = open != null) { vm.closePerson() }

    var renameTarget by remember { mutableStateOf<PersonRow?>(null) }

    if (open == null) {
        PeopleGrid(
            persons = vm.persons,
            onOpen = vm::open,
            onRename = { renameTarget = it },
        )
    } else {
        PersonCropsScreen(
            person = open,
            crops = vm.personCrops,
            selection = selection,
            onSelectionChange = onSelectionChange,
            onCreatePack = onCreatePack,
            onBack = vm::closePerson,
            onRename = { renameTarget = open },
        )
    }

    renameTarget?.let { person ->
        RenamePersonDialog(
            person = person,
            onDismiss = { renameTarget = null },
            onRename = { name ->
                vm.rename(person.id, name)
                renameTarget = null
            },
        )
    }
}

@Composable
private fun PeopleGrid(
    persons: List<PersonRow>,
    onOpen: (PersonRow) -> Unit,
    onRename: (PersonRow) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text(
            "People",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(vertical = 12.dp),
        )

        if (persons.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No people yet — scan first.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(140.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                items(persons, key = { it.id }) { person ->
                    PersonCard(
                        person = person,
                        onClick = { onOpen(person) },
                        onLongClick = { onRename(person) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PersonCard(
    person: PersonRow,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(Color(0xFF2A2A2E)),
            contentAlignment = Alignment.Center,
        ) {
            if (person.coverCropPath != null) {
                AsyncImage(
                    model = File(person.coverCropPath),
                    contentDescription = PeopleViewModel.displayName(person),
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                )
            } else {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(0.4f),
                )
            }
        }
        Column(Modifier.padding(8.dp)) {
            Text(
                PeopleViewModel.displayName(person),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
            )
            Text(
                if (person.faceCount == 1) "1 face" else "${person.faceCount} faces",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PersonCropsScreen(
    person: PersonRow,
    crops: List<CandidCrop>,
    selection: SelectionState,
    onSelectionChange: (SelectionState) -> Unit,
    onCreatePack: (String) -> Unit,
    onBack: () -> Unit,
    onRename: () -> Unit,
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to people")
                }
                Text(
                    PeopleViewModel.displayName(person),
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRename) {
                    Icon(Icons.Default.Edit, contentDescription = "Rename person")
                }
            }
        }

        if (crops.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No stickers for this person yet.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                items(crops, key = { it.id }) { crop ->
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
        CropDetailDialog(
            crop = crop,
            personName = PeopleViewModel.displayName(person),
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

@Composable
private fun RenamePersonDialog(
    person: PersonRow,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember(person.id) { mutableStateOf(person.name.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename ${PeopleViewModel.displayName(person)}") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
