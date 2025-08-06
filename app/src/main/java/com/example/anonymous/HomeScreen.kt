package com.example.anonymous

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HomeScreen(
    isCommunity: Boolean = false,
    onOpenChat: (String) -> Unit,
    onOpenCommunity: (CommunityInfo) -> Unit
) {
    // Dialog + input state
    var showNewContactDialog by remember { mutableStateOf(false) }
    var newContactName by remember { mutableStateOf("") }

    var showAddCommunityDialog by remember { mutableStateOf(false) }
    var newCommunityName by remember { mutableStateOf("") }
    var newCommunityDescription by remember { mutableStateOf("") }

    // Contacts
    val contactNames = remember {
        mutableStateMapOf<String, String>().apply {
            listOf("Si", "No", "Lui", "GianFranco", "Luigi", "Squirty", "GG")
                .forEach { put(it, it) }
        }
    }

    // Communities (initial + dynamic additions)
    val communityList = remember {
        mutableStateListOf(
            CommunityInfo("Anonymous", "Community for Anonymous thinkers.", 1500),
            CommunityInfo("Science", "Discuss new discoveries.", 2350),
            CommunityInfo("Hacking", "Cyber-security & hacking tips.", 980),
            CommunityInfo("Building", "DIY enthusiasts hub.", 1200)
        )
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = {
                if (isCommunity) showAddCommunityDialog = true
                else showNewContactDialog = true
            }) {
                Icon(Icons.Default.Add, contentDescription = "Add")
            }
        }
    ) { _ ->
        Box() {
            if (isCommunity) {
                CommunityList(communityList, onOpenCommunity)
            } else {
                ContactList(contactNames, onOpenChat)
            }
        }
    }

    // Add Contact Dialog
    if (showNewContactDialog) {
        NewContactDialog(
            onAdd = { uuid, friendlyName ->
                contactNames[uuid] = friendlyName
            },
            onDismiss = {
                showNewContactDialog = false
            }
        )
    }

    //  Add Community Dialog
    if (showAddCommunityDialog) {
        ColumnInputDialog(
            title = "Add Community",
            firstLabel = "Community Name",
            firstValue = newCommunityName,
            onFirstChange = { newCommunityName = it },
            secondLabel = "Description",
            secondValue = newCommunityDescription,
            onSecondChange = { newCommunityDescription = it },
            onConfirm = {
                if (newCommunityName.isNotBlank()) {
                    communityList += CommunityInfo(
                        name = newCommunityName,
                        description = newCommunityDescription,
                        members = 0
                    )
                }
                newCommunityName = ""
                newCommunityDescription = ""
                showAddCommunityDialog = false
            },
            onDismiss = {
                newCommunityName = ""
                newCommunityDescription = ""
                showAddCommunityDialog = false
            }
        )
    }
}

@Composable
private fun CommunityList(
    communities: List<CommunityInfo>,
    onOpenCommunity: (CommunityInfo) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(communities) { community ->
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clickable { onOpenCommunity(community) },
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = community.name,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = community.description,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactList(
    contacts: Map<String, String>,
    onOpenChat: (String) -> Unit
) {
    var expandedIndex by remember { mutableStateOf(-1) }
    var showEdit by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    var currentKey by remember { mutableStateOf("") }
    var tempName by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        itemsIndexed(contacts.keys.toList()) { idx, key ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenChat(key) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(text = contacts[key] ?: key)
                    Text(
                        text = "Hell yeah!",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Spacer(Modifier.weight(1f))

                // ← FIXED: pass onClick explicitly
                IconButton(onClick = {
                    expandedIndex = if (expandedIndex == idx) -1 else idx
                }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                }

                DropdownMenu(
                    expanded = expandedIndex == idx,
                    onDismissRequest = { expandedIndex = -1 }
                ) {
                    // ← FIXED: use named params text + onClick
                    DropdownMenuItem(
                        text = { Text("Edit Name") },
                        onClick = {
                            currentKey = key
                            tempName = contacts[key] ?: key
                            showEdit = true
                            expandedIndex = -1
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete Contact") },
                        onClick = {
                            currentKey = key
                            showDelete = true
                            expandedIndex = -1
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Report", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            currentKey = key
                            showReport = true
                            expandedIndex = -1
                        }
                    )
                }
            }
        }
    }

    // Edit Dialog
    if (showEdit) {
        SimpleInputDialog(
            title = "Edit Contact Name",
            label = "New Name",
            value = tempName,
            onValueChange = { tempName = it },
            onConfirm = {
                (contacts as MutableMap)[currentKey] = tempName
                showEdit = false
            },
            onDismiss = { showEdit = false }
        )
    }

    // Delete Dialog
    if (showDelete) {
        ConfirmDialog(
            title = "Confirm Delete",
            text = "Delete $currentKey?",
            onConfirm = {
                (contacts as MutableMap).remove(currentKey)
                showDelete = false
            },
            onDismiss = { showDelete = false }
        )
    }

    // Report Dialog
    if (showReport) {
        ConfirmDialog(
            title = "Report Contact",
            text = "Report $currentKey?",
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            onConfirm = {
                (contacts as MutableMap)[currentKey] = "$currentKey Reported"
                showReport = false
            },
            onDismiss = { showReport = false }
        )
    }
}

@Composable
private fun SimpleInputDialog(
    title: String,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            TextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label) }
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ColumnInputDialog(
    title: String,
    firstLabel: String,
    firstValue: String,
    onFirstChange: (String) -> Unit,
    secondLabel: String,
    secondValue: String,
    onSecondChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                TextField(
                    value = firstValue,
                    onValueChange = onFirstChange,
                    label = { Text(firstLabel) }
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = secondValue,
                    onValueChange = onSecondChange,
                    label = { Text(secondLabel) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    icon: (@Composable () -> Unit)? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon?.invoke()
                if (icon != null) Spacer(Modifier.width(8.dp))
                Text(text)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Yes") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("No") }
        }
    )
}