package com.example.anonymous

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HomeScreen(
    isCommunity: Boolean = false,
    onOpenChat: (String) -> Unit,
    onOpenCommunity: (CommunityInfo) -> Unit
) {
    val context = LocalContext.current
    if (isCommunity) {
        // Create a list of community objects.
        val communityList = listOf(
            CommunityInfo(
                name = "Anonymous",
                description = "Community for Anonymous thinkers.",
                members = 1500
            ),
            CommunityInfo(
                name = "Science",
                description = "Join the Science community to discuss new discoveries.",
                members = 2350
            ),
            CommunityInfo(
                name = "Hacking",
                description = "Discuss cybersecurity, hacking techniques and more.",
                members = 980
            ),
            CommunityInfo(
                name = "Building",
                description = "Community of builders, makers, and DIY enthusiasts.",
                members = 1200
            )
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(communityList) { community ->
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clickable {
                            // Open the detailed CommunityScreen.
                            onOpenCommunity(community)
                        },
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
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Join the ${community.name} community!",
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    } else {
        // Contacts branch.
        val contactNames = remember {
            mutableStateMapOf<String, String>().apply {
                listOf("Si", "No", "Lui", "GianFranco", "Luigi", "Squirty", "GG").forEach {
                    put(it, it)
                }
            }
        }
        val expandedItemIndex = remember { mutableStateOf(-1) }
        val showEditDialog = remember { mutableStateOf(false) }
        val showDeleteDialog = remember { mutableStateOf(false) }
        val showReportDialog = remember { mutableStateOf(false) }
        val currentContact = remember { mutableStateOf("") }
        val newName = remember { mutableStateOf("") }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(30.dp)
        ) {
            itemsIndexed(contactNames.keys.toList()) { index, contact ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clickable { onOpenChat(contact) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = null,
                        modifier = Modifier.size(50.dp)
                    )
                    Column(
                        modifier = Modifier.padding(start = 12.dp)
                    ) {
                        Text(
                            text = contactNames[contact] ?: contact,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "Hell yeah!",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Box {
                        IconButton(onClick = {
                            expandedItemIndex.value =
                                if (expandedItemIndex.value == index) -1 else index
                        }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(
                            expanded = expandedItemIndex.value == index,
                            onDismissRequest = { expandedItemIndex.value = -1 }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Edit Name") },
                                onClick = {
                                    currentContact.value = contact
                                    newName.value = contactNames[contact] ?: contact
                                    showEditDialog.value = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete Contact") },
                                onClick = {
                                    currentContact.value = contact
                                    showDeleteDialog.value = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Report", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    currentContact.value = contact
                                    showReportDialog.value = true
                                }
                            )
                        }
                    }
                }
            }
        }
        if (showEditDialog.value) {
            AlertDialog(
                onDismissRequest = { showEditDialog.value = false },
                title = { Text("Edit Contact Name") },
                text = {
                    Column {
                        TextField(
                            value = newName.value,
                            onValueChange = { newName.value = it }
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        contactNames[currentContact.value] = newName.value
                        showEditDialog.value = false
                    }) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    Button(onClick = { showEditDialog.value = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
        if (showDeleteDialog.value) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog.value = false },
                title = { Text("Confirm Delete") },
                text = { Text("Are you sure you want to delete ${currentContact.value}?") },
                confirmButton = {
                    Button(onClick = {
                        contactNames.remove(currentContact.value)
                        showDeleteDialog.value = false
                    }) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    Button(onClick = { showDeleteDialog.value = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
        if (showReportDialog.value) {
            AlertDialog(
                onDismissRequest = { showReportDialog.value = false },
                title = { Text("Confirm Report") },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Warning Icon",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = currentContact.value,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        contactNames[currentContact.value] = "${currentContact.value} Reported"
                        showReportDialog.value = false
                    }) {
                        Text("Report")
                    }
                },
                dismissButton = {
                    Button(onClick = { showReportDialog.value = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}