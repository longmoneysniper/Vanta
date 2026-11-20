package com.vanta.media.ui.settings

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vanta.media.ui.theme.*

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var pinInput by remember { mutableStateOf("") }
    var kodiZipUrl by remember { mutableStateOf("") }
    var punchToken by remember { mutableStateOf("") }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { viewModel.exportSafBackup(it) } }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.restoreSafBackup(it) } }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Text("Vanta Settings & Extensions", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text(state.statusBanner, style = MaterialTheme.typography.labelMedium, color = AmberCorona)
        }

        // 1. Chaquopy Python Bridge & Kodi Repo Installer
        item {
            SettingsCard(title = "CHAQUOPY PYTHON BRIDGE & KODI REPO INSTALLER", icon = Icons.Rounded.Terminal) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = kodiZipUrl,
                        onValueChange = { kodiZipUrl = it },
                        placeholder = { Text("https://.../repository.example.zip") },
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            if (kodiZipUrl.isNotBlank()) {
                                viewModel.installKodiRepo(kodiZipUrl)
                                kodiZipUrl = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AmberCorona, contentColor = OledObsidian)
                    ) { Text("Install Zip") }
                }
                state.kodiAddons.forEach { addon ->
                    Text("● ${addon.name} (${addon.id} v${addon.version})", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // 2. PunchPlay.tv OAuth & Device Sync
        item {
            SettingsCard(title = "PUNCHPLAY.TV OAUTH & TMDB SCROBBLING", icon = Icons.Rounded.Sync) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.startPunchPlayOAuth() },
                        colors = ButtonDefaults.buttonColors(containerColor = AmberCorona, contentColor = OledObsidian)
                    ) { Text("Get Device Code") }
                    OutlinedTextField(
                        value = punchToken,
                        onValueChange = { punchToken = it },
                        placeholder = { Text(if (state.punchPlayLinked) "Linked • Paste Token to Update" else "Or Paste OAuth Token") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = {
                        if (punchToken.isNotBlank()) {
                            viewModel.savePunchPlayManualToken(punchToken)
                            punchToken = ""
                        }
                    }) { Text("Link") }
                }
                state.punchPlayCode?.let { code ->
                    Text("Code: ${code.userCode} • Activate at ${code.verificationUrl}", color = AmberCorona, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // 3. Kids PIN Lock & Spoiler-Free Mode
        item {
            SettingsCard(title = "PARENTAL PIN LOCK & SPOILER-FREE MODE", icon = Icons.Rounded.Shield) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { if (it.length <= 4) pinInput = it },
                        placeholder = { Text("4-Digit PIN") },
                        modifier = Modifier.width(130.dp)
                    )
                    OutlinedButton(onClick = {
                        viewModel.setKidsPin(pinInput)
                        pinInput = ""
                    }) { Text("Set PIN") }

                    Button(
                        onClick = {
                            viewModel.toggleKidsModeWithPinCheck(!state.kidsModeEnabled, pinInput)
                            pinInput = ""
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AmberCorona, contentColor = OledObsidian)
                    ) { Text(if (state.kidsModeEnabled) "Unlock Kids Mode" else "Lock Kids Mode") }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Spoiler-Free Mode (Blur Unwatched Episode Stills & Plots)", color = TextPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = state.spoilerFreeEnabled, onCheckedChange = { viewModel.toggleSpoilerFree(it) })
                }
            }
        }

        // 4. SAF Backup & Restore
        item {
            SettingsCard(title = "STORAGE ACCESS FRAMEWORK (SAF) BACKUP & RESTORE", icon = Icons.Rounded.Backup) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { exportLauncher.launch("vanta_backup.json") },
                        colors = ButtonDefaults.buttonColors(containerColor = AmberCorona, contentColor = OledObsidian)
                    ) { Text("Export Backup") }

                    OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("application/json")) }) {
                        Text("Restore Backup")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
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
