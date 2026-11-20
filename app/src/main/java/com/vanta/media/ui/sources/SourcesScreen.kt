package com.vanta.media.ui.sources

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vanta.media.ui.theme.*

@Composable
fun SourcesScreen(viewModel: SourcesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var jfUrl by remember { mutableStateOf("http://192.168.1.100:8096") }
    var jfUser by remember { mutableStateOf("") }
    var jfPass by remember { mutableStateOf("") }
    var addonManifest by remember { mutableStateOf("") }
    var debridToken by remember { mutableStateOf("") }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.indexLocalVideoUri(uri, uri.lastPathSegment ?: "Local_Video.mkv")
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Text("Unified Sources Hub", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text(state.statusMessage, style = MaterialTheme.typography.labelMedium, color = AmberCorona)
        }

        // 1. Jellyfin Multi-Server & Quick Connect Card
        item {
            SourceGlassCard(title = "JELLYFIN SERVER & QUICK CONNECT", icon = Icons.Rounded.Dns) {
                OutlinedTextField(value = jfUrl, onValueChange = { jfUrl = it }, label = { Text("Server URL") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = jfUser, onValueChange = { jfUser = it }, label = { Text("Username") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = jfPass, onValueChange = { jfPass = it }, label = { Text("Password") }, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.connectJellyfinPassword(jfUrl, jfUser, jfPass) },
                        colors = ButtonDefaults.buttonColors(containerColor = AmberCorona, contentColor = OledObsidian)
                    ) { Text("Connect & Sync") }

                    OutlinedButton(onClick = {
                        if (state.quickConnect == null) viewModel.startJellyfinQuickConnect(jfUrl)
                        else viewModel.verifyQuickConnect(jfUrl)
                    }) {
                        Text(state.quickConnect?.let { "Verify Code: ${it.code}" } ?: "Quick Connect")
                    }
                }
                state.jellyfinServers.forEach { srv ->
                    Text("● Connected: ${srv.name} (${srv.baseUrl})", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // 2. Stremio & Nuvio Parallel Addons
        item {
            SourceGlassCard(title = "STREMIO & NUVIO PARALLEL ADDONS", icon = Icons.Rounded.Extension) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = addonManifest,
                        onValueChange = { addonManifest = it },
                        placeholder = { Text("https://.../manifest.json") },
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            if (addonManifest.isNotBlank()) {
                                viewModel.installStremioAddon(addonManifest)
                                addonManifest = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AmberCorona, contentColor = OledObsidian)
                    ) { Text("Install") }
                }
                state.installedAddons.forEach { addon ->
                    Text("● ${addon.name} (${if (addon.isNuvio) "Nuvio" else "Stremio"})", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // 3. Debrid Cloud Browser
        item {
            SourceGlassCard(title = "DEBRID CLOUD BROWSER (REAL-DEBRID)", icon = Icons.Rounded.CloudDownload) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = debridToken,
                        onValueChange = { debridToken = it },
                        placeholder = { Text(if (state.hasDebridToken) "Token Encrypted • Paste to Replace" else "Paste Real-Debrid API Token") },
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            if (debridToken.isNotBlank()) {
                                viewModel.saveAndSyncDebrid(debridToken)
                                debridToken = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AmberCorona, contentColor = OledObsidian)
                    ) { Text("Sync Cloud") }
                }
                state.debridItems.take(5).forEach { file ->
                    Text("● ${file.filename}", color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        }

        // 4. Local & SMB Storage
        item {
            SourceGlassCard(title = "LOCAL DEVICE & SMB STORAGE", icon = Icons.Rounded.FolderOpen) {
                OutlinedButton(onClick = { filePicker.launch(arrayOf("video/*")) }) {
                    Icon(Icons.Rounded.VideoFile, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Pick Video via SAF Range Proxy")
                }
            }
        }
    }
}

@Composable
private fun SourceGlassCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(FrostedGlassFill)
            .border(1.dp, FrostedBorder, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = AmberCorona)
            Text(title, color = AmberCorona, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
        content()
    }
}
