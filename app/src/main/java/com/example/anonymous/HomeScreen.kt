package com.example.anonymous

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.anonymous.community.CommunityInvite
import com.example.anonymous.community.createCommunity
import com.example.anonymous.community.joinCommunity
import com.example.anonymous.network.model.Community
import com.example.anonymous.network.model.Contact
import com.example.anonymous.repository.CommunityRepository
import com.example.anonymous.repository.ContactRepository
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    isCommunity: Boolean = false,
    onOpenChat: (Contact) -> Unit,
    onOpenCommunity: (Community) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val contactRepository = remember { ContactRepository(context) }
    val communityRepository = remember { CommunityRepository.getInstance(context) }

    val contacts by contactRepository.getContactsFlow().collectAsState(initial = emptyList())
    val communities by communityRepository.getCommunitiesFlow().collectAsState(initial = emptyList())

    var showNewContactDialog by remember { mutableStateOf(false) }
    var showCreateCommunityDialog by remember { mutableStateOf(false) }
    var showJoinCommunityDialog by remember { mutableStateOf(false) }
    var showCommunityMenu by remember { mutableStateOf(false) }

    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var currentContact by remember { mutableStateOf<Contact?>(null) }
    var tempName by remember { mutableStateOf("") }

    Scaffold(
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = {
                    if (isCommunity) showCommunityMenu = true
                    else showNewContactDialog = true
                }) {
                    Icon(Icons.Default.Add, contentDescription = "Add")
                }
                DropdownMenu(
                    expanded = showCommunityMenu,
                    onDismissRequest = { showCommunityMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Create Community") },
                        onClick = { showCommunityMenu = false; showCreateCommunityDialog = true }
                    )
                    DropdownMenuItem(
                        text = { Text("Join via Invite") },
                        onClick = { showCommunityMenu = false; showJoinCommunityDialog = true }
                    )
                }
            }
        }
    ) { _ ->
        if (isCommunity) {
            CommunityList(communities = communities, onOpenCommunity = onOpenCommunity)
        } else {
            ContactList(
                contacts = contacts,
                onOpenChat = onOpenChat,
                onEditClick = { contact ->
                    currentContact = contact
                    tempName = contact.name
                    showEditDialog = true
                },
                onDeleteClick = { contact ->
                    currentContact = contact
                    showDeleteDialog = true
                },
                onReportClick = { contact ->
                    currentContact = contact
                    showReportDialog = true
                }
            )
        }
    }

    if (showNewContactDialog) {
        NewContactDialog(
            onDismiss = { showNewContactDialog = false },
            onContactAdded = { /* Already handled by flow */ }
        )
    }

    if (showCreateCommunityDialog) {
        CreateCommunityDialog(
            onDismiss = { showCreateCommunityDialog = false },
            onCreate = {name ->
                showCreateCommunityDialog = false
                coroutineScope.launch {
                    val result = createCommunity(context, name)
                    if (result.isSuccess) {
                        Toast.makeText(context, "Community created!", Toast.LENGTH_SHORT).show()
                        onOpenCommunity(result.getOrThrow())
                    } else {
                        Toast.makeText(context, "Failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    if (showJoinCommunityDialog) {
        JoinCommunityDialog(
            onDismiss = { showJoinCommunityDialog = false },
            onJoin = { invite ->
                showJoinCommunityDialog = false
                coroutineScope.launch {
                    val parsed = CommunityInvite.parseInviteUri(invite)
                    if (parsed == null) {
                        Toast.makeText(context, "Invalid invite", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val myB32 = contactRepository.getMyIdentity()?.b32Address ?: ""
                    val myName = if (myB32.isNotEmpty()) myB32.take(12) else "Unknown"

                    val result = joinCommunity(
                        context = context,
                        invite = parsed,
                        myB32 = myB32,
                        myName = myName,
                        onMessage = { }
                    )
                    if (result.isSuccess) {
                        val (community, tempClient) = result.getOrThrow()
                        tempClient.disconnect()
                        Toast.makeText(context, "Joined ${community.name}!", Toast.LENGTH_SHORT).show()
                        onOpenCommunity(community)
                    } else {
                        Toast.makeText(context, "Join failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    if (showEditDialog && currentContact != null) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Edit Contact") },
            text = {
                TextField(
                    value = tempName,
                    onValueChange = { tempName = it },
                    label = { Text("Contact Name") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            contactRepository.updateContact(
                                currentContact!!.copy(name = tempName)
                            )
                        }
                        showEditDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDeleteDialog && currentContact != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Contact") },
            text = { Text("Are you sure you want to delete ${currentContact?.name}?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            contactRepository.deleteContact(currentContact!!.b32Address)
                        }
                        showDeleteDialog = false
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showReportDialog && currentContact != null) {
        AlertDialog(
            onDismissRequest = { showReportDialog = false },
            title = { Text("Report Contact") },
            text = { Text("Are you sure you want to report ${currentContact?.name}?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Handle report logic
                        showReportDialog = false
                    }
                ) {
                    Text("Report")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReportDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun CreateCommunityDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String) -> Unit
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Community") },
        text = {
            Column {
                Text("Choose a name for your community. You will be the host.", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(12.dp))
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Community Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onCreate(name.trim()) },
                enabled = name.isNotBlank()
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun JoinCommunityDialog(
    onDismiss: () -> Unit,
    onJoin: (invite: String) -> Unit
) {
    var uri by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join Community") },
        text = {
            Column {
                Text("Scan the invite QR:", style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun CommunityList(
    communities: List<Community>,
    onOpenCommunity: (Community) -> Unit
) {
    if (communities.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No communities yet", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Tap + to create one or join via an invite.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        }
        return
    }
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
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onOpenCommunity(community) },
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = community.name,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = community.b32Address.take(20) + "...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                    if (community.isCreator) {
                        Badge(containerColor = MaterialTheme.colorScheme.secondary) { Text("Host", fontSize = 10.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactList(
    contacts: List<Contact>,
    onOpenChat: (Contact) -> Unit,
    onEditClick: (Contact) -> Unit,
    onDeleteClick: (Contact) -> Unit,
    onReportClick: (Contact) -> Unit
) {
    if (contacts.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No Contacts yet", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Tap + to add your friends.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(contacts) { contact ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onOpenChat(contact) }
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
                    Text(text = contact.name)
                    Text(
                        text = contact.lastMessage ?: "No messages yet",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Spacer(Modifier.weight(1f))

                Box {
                    var expanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { expanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                    }

                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Edit Name") },
                            onClick = {
                                onEditClick(contact)
                                expanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete Contact") },
                            onClick = {
                                onDeleteClick(contact)
                                expanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Report",
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                onReportClick(contact)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}