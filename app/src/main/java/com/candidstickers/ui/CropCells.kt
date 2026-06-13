package com.candidstickers.ui

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.candidstickers.data.CandidCrop
import java.io.File

/** Square sticker thumbnail with reason line and up to [MAX_CELL_TAGS] tag chips. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CropCell(
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
        if (crop.tags.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                crop.tags.take(MAX_CELL_TAGS).forEach { TagChip(it) }
            }
        }
    }
}

const val MAX_CELL_TAGS = 2

@Composable
fun TagChip(tag: String) {
    Text(
        tag,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        modifier = Modifier
            .background(Color(0xFF3A3A42), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/** Original photo + all tags + person name (when clustered). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CropDetailDialog(
    crop: CandidCrop,
    personName: String?,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onShare) { Text("Share") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("${crop.reason} · ${"%.0f".format(crop.score * 100)}%") },
        text = {
            Column {
                AsyncImage(
                    model = Uri.parse(crop.contentUri),
                    contentDescription = "Original photo",
                    modifier = Modifier.fillMaxWidth(),
                )
                if (personName != null) {
                    Text(
                        personName,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (crop.tags.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        crop.tags.forEach { TagChip(it) }
                    }
                }
            }
        },
    )
}
