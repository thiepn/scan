package com.thiepn.scan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thiepn.scan.data.DocumentType
import com.thiepn.scan.data.FolderEntity
import com.thiepn.scan.data.LibrarySort
import com.thiepn.scan.data.SmartCollection
import com.thiepn.scan.data.TagEntity

data class OrganizationFilterState(
    val smartCollection: SmartCollection = SmartCollection.ALL,
    val folderId: String? = null,
    val tagId: String? = null,
    val documentType: DocumentType? = null,
    val sort: LibrarySort = LibrarySort.RELEVANCE
)

data class BulkOrganizationChange(
    val changeFolder: Boolean,
    val folderId: String?,
    val changeType: Boolean,
    val documentType: DocumentType?,
    val changeTags: Boolean,
    val tagIds: List<String>,
    val changeReview: Boolean,
    val needsReview: Boolean
)

@Composable
fun OrganizationFilterDialog(
    state: OrganizationFilterState,
    folders: List<FolderEntity>,
    tags: List<TagEntity>,
    queryActive: Boolean,
    onDismiss: () -> Unit,
    onApply: (OrganizationFilterState) -> Unit
) {
    var draft by remember(state) { mutableStateOf(state) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sort & filter") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SectionTitle("Smart collection")
                SmartCollection.entries.forEach { option ->
                    RadioRow(
                        selected = draft.smartCollection == option,
                        label = option.label,
                        onClick = { draft = draft.copy(smartCollection = option) }
                    )
                }

                HorizontalDivider()
                SectionTitle("Folder")
                RadioRow(
                    selected = draft.folderId == null,
                    label = "Any folder",
                    onClick = { draft = draft.copy(folderId = null) }
                )
                folderRows(folders).forEach { row ->
                    RadioRow(
                        selected = draft.folderId == row.folder.id,
                        label = row.label,
                        onClick = { draft = draft.copy(folderId = row.folder.id) }
                    )
                }

                HorizontalDivider()
                SectionTitle("Tag")
                RadioRow(
                    selected = draft.tagId == null,
                    label = "Any tag",
                    onClick = { draft = draft.copy(tagId = null) }
                )
                tags.forEach { tag ->
                    RadioRow(
                        selected = draft.tagId == tag.id,
                        label = "#${tag.name}",
                        onClick = { draft = draft.copy(tagId = tag.id) }
                    )
                }

                HorizontalDivider()
                SectionTitle("Document type")
                RadioRow(
                    selected = draft.documentType == null,
                    label = "Any type",
                    onClick = { draft = draft.copy(documentType = null) }
                )
                DocumentType.entries.forEach { type ->
                    RadioRow(
                        selected = draft.documentType == type,
                        label = type.label,
                        onClick = { draft = draft.copy(documentType = type) }
                    )
                }

                HorizontalDivider()
                SectionTitle("Sort")
                LibrarySort.entries.forEach { option ->
                    if (option != LibrarySort.RELEVANCE || queryActive) {
                        RadioRow(
                            selected = draft.sort == option,
                            label = option.label,
                            onClick = { draft = draft.copy(sort = option) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(draft) }) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun FolderManagerDialog(
    folders: List<FolderEntity>,
    onDismiss: () -> Unit,
    onCreate: (String, String?) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var parentId by remember { mutableStateOf<String?>(null) }
    var renameId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Folders") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SectionTitle("Create folder")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Folder name") }
                )
                Text("Parent", fontWeight = FontWeight.Medium)
                RadioRow(
                    selected = parentId == null,
                    label = "Top level",
                    onClick = { parentId = null }
                )
                folderRows(folders).forEach { row ->
                    RadioRow(
                        selected = parentId == row.folder.id,
                        label = row.label,
                        onClick = { parentId = row.folder.id }
                    )
                }
                TextButton(
                    onClick = {
                        onCreate(name, parentId)
                        name = ""
                    },
                    enabled = name.isNotBlank()
                ) { Text("Create") }

                HorizontalDivider()
                SectionTitle("Existing folders")
                if (folders.isEmpty()) {
                    Text("No folders yet.")
                } else {
                    folderRows(folders).forEach { row ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                        ) {
                            if (renameId == row.folder.id) {
                                OutlinedTextField(
                                    value = renameText,
                                    onValueChange = { renameText = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text("Rename ${row.folder.name}") }
                                )
                                Row {
                                    TextButton(
                                        onClick = {
                                            onRename(row.folder.id, renameText)
                                            renameId = null
                                            renameText = ""
                                        },
                                        enabled = renameText.isNotBlank()
                                    ) { Text("Save") }
                                    TextButton(onClick = {
                                        renameId = null
                                        renameText = ""
                                    }) { Text("Cancel") }
                                }
                            } else {
                                Text(row.label)
                                Row {
                                    TextButton(onClick = {
                                        renameId = row.folder.id
                                        renameText = row.folder.name
                                    }) { Text("Rename") }
                                    TextButton(onClick = { onDelete(row.folder.id) }) {
                                        Text("Delete")
                                    }
                                }
                            }
                        }
                    }
                }
                Text(
                    "Deleting a folder keeps all documents. Its documents and child folders move to the deleted folder's parent."
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
fun TagManagerDialog(
    tags: List<TagEntity>,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tags") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("New tag") }
                )
                TextButton(
                    onClick = {
                        onCreate(name)
                        name = ""
                    },
                    enabled = name.isNotBlank()
                ) { Text("Create tag") }

                HorizontalDivider()
                if (tags.isEmpty()) {
                    Text("No tags yet.")
                } else {
                    tags.forEach { tag ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("#${tag.name}", modifier = Modifier.weight(1f))
                            TextButton(onClick = { onDelete(tag.id) }) { Text("Delete") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
fun BulkOrganizeDialog(
    selectedCount: Int,
    folders: List<FolderEntity>,
    tags: List<TagEntity>,
    onDismiss: () -> Unit,
    onApply: (BulkOrganizationChange) -> Unit
) {
    var changeFolder by remember { mutableStateOf(false) }
    var folderId by remember { mutableStateOf<String?>(null) }
    var changeType by remember { mutableStateOf(false) }
    var documentType by remember { mutableStateOf<DocumentType?>(null) }
    var changeTags by remember { mutableStateOf(false) }
    var selectedTags by remember { mutableStateOf<Set<String>>(emptySet()) }
    var changeReview by remember { mutableStateOf(false) }
    var needsReview by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Organize $selectedCount document${if (selectedCount == 1) "" else "s"}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToggleRow(
                    checked = changeFolder,
                    label = "Change folder",
                    onChange = { changeFolder = it }
                )
                if (changeFolder) {
                    RadioRow(
                        selected = folderId == null,
                        label = "Unfiled",
                        onClick = { folderId = null }
                    )
                    folderRows(folders).forEach { row ->
                        RadioRow(
                            selected = folderId == row.folder.id,
                            label = row.label,
                            onClick = { folderId = row.folder.id }
                        )
                    }
                }

                HorizontalDivider()
                ToggleRow(
                    checked = changeType,
                    label = "Change document type",
                    onChange = { changeType = it }
                )
                if (changeType) {
                    DocumentType.entries.forEach { type ->
                        RadioRow(
                            selected = documentType == type,
                            label = type.label,
                            onClick = { documentType = type }
                        )
                    }
                }

                HorizontalDivider()
                ToggleRow(
                    checked = changeTags,
                    label = "Replace tags",
                    onChange = { changeTags = it }
                )
                if (changeTags) {
                    if (tags.isEmpty()) {
                        Text("No tags exist yet. Create tags from the library toolbar first.")
                    } else {
                        tags.forEach { tag ->
                            CheckRow(
                                checked = tag.id in selectedTags,
                                label = "#${tag.name}",
                                onChange = { checked ->
                                    selectedTags = if (checked) {
                                        selectedTags + tag.id
                                    } else {
                                        selectedTags - tag.id
                                    }
                                }
                            )
                        }
                    }
                }

                HorizontalDivider()
                ToggleRow(
                    checked = changeReview,
                    label = "Change review state",
                    onChange = { changeReview = it }
                )
                if (changeReview) {
                    RadioRow(
                        selected = !needsReview,
                        label = "Reviewed",
                        onClick = { needsReview = false }
                    )
                    RadioRow(
                        selected = needsReview,
                        label = "Needs review",
                        onClick = { needsReview = true }
                    )
                }
            }
        },
        confirmButton = {
            val hasChange = changeFolder || changeType || changeTags || changeReview
            TextButton(
                onClick = {
                    onApply(
                        BulkOrganizationChange(
                            changeFolder = changeFolder,
                            folderId = folderId,
                            changeType = changeType,
                            documentType = documentType,
                            changeTags = changeTags,
                            tagIds = selectedTags.toList(),
                            changeReview = changeReview,
                            needsReview = needsReview
                        )
                    )
                },
                enabled = hasChange && (!changeType || documentType != null)
            ) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

data class FolderRow(
    val folder: FolderEntity,
    val depth: Int,
    val label: String
)

fun folderRows(folders: List<FolderEntity>): List<FolderRow> {
    val children = folders.groupBy { it.parentId }
    val visited = mutableSetOf<String>()
    val rows = mutableListOf<FolderRow>()

    fun visit(parentId: String?, depth: Int) {
        children[parentId]
            .orEmpty()
            .sortedBy { it.name.lowercase() }
            .forEach { folder ->
                if (!visited.add(folder.id)) return@forEach
                rows += FolderRow(
                    folder = folder,
                    depth = depth,
                    label = "${"  ".repeat(depth)}${if (depth > 0) "↳ " else ""}${folder.name}"
                )
                visit(folder.id, depth + 1)
            }
    }

    visit(null, 0)
    folders.filter { it.id !in visited }
        .sortedBy { it.name.lowercase() }
        .forEach { folder ->
            rows += FolderRow(folder, 0, folder.name)
        }
    return rows
}

fun folderPath(folderId: String?, folders: List<FolderEntity>): String? {
    folderId ?: return null
    val byId = folders.associateBy { it.id }
    val parts = mutableListOf<String>()
    val visited = mutableSetOf<String>()
    var current = byId[folderId]
    while (current != null && visited.add(current.id)) {
        parts += current.name
        current = current.parentId?.let(byId::get)
    }
    return parts.asReversed().joinToString(" / ").takeIf { it.isNotBlank() }
}

fun folderAndDescendantIds(folderId: String, folders: List<FolderEntity>): Set<String> {
    val children = folders.groupBy { it.parentId }
    val result = linkedSetOf<String>()
    fun visit(id: String) {
        if (!result.add(id)) return
        children[id].orEmpty().forEach { visit(it.id) }
    }
    visit(folderId)
    return result
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun RadioRow(
    selected: Boolean,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun ToggleRow(
    checked: Boolean,
    label: String,
    onChange: (Boolean) -> Unit
) {
    CheckRow(checked, label, onChange)
}

@Composable
private fun CheckRow(
    checked: Boolean,
    label: String,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}
