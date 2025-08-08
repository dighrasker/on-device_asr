package com.example.my_app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem  // dropdown feature (stable)
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
//import androidx.compose.material3.ExposedDropdownMenu


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: WhisperViewModel = viewModel()
            RecordingAppUI(vm)
        }
    }
}


@Composable
fun RecordingAppUI(viewModel: WhisperViewModel) {
    val context = LocalContext.current

    // ------- Observe ViewModel state -------
    val isRecording by viewModel.isRecording.collectAsState()
    val transcriptText by viewModel.transcript.collectAsState()
    val errorMsg by viewModel.error.collectAsState()

    // Create a TextFieldValue for multiline support
    var transcript by remember { mutableStateOf(TextFieldValue(text = transcriptText)) }
    LaunchedEffect(transcriptText) {
        transcript = transcript.copy(text = transcriptText)
    }

    // Dropdown state for selecting asset .wav files  // dropdown feature (stable)
    var assetFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var expanded by remember { mutableStateOf(false) }
    var selectedFile by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        context.assets.list("samples")
            ?.filter { it.endsWith(".wav") }
            ?.let { files ->
                assetFiles = files
                if (files.isNotEmpty()) selectedFile = files[0]
            }
    }

    // NEW: Language / Model / Translate UI state
    val languageOptions = listOf("auto", "english", "hindi", "french", "german", "spanish", "chinese") // NEW
    var languageExpanded by remember { mutableStateOf(false) }                                          // NEW
    var selectedLanguage by remember { mutableStateOf("auto") }                                         // NEW

    val modelOptions = listOf("tiny", "tiny.en", "base", "base.en")                                     // NEW
    var modelExpanded by remember { mutableStateOf(false) }                                             // NEW
    var selectedModel by remember { mutableStateOf("tiny") }                                            // NEW

    var translateEnabled by remember { mutableStateOf(false) }


    // Permission launcher
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startRecording(context)
        else viewModel.setError("Microphone permission denied.")
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.LightGray),
        contentAlignment = Alignment.Center
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {

            // ─── Multiline Transcript display ─────────────────
            OutlinedTextField(
                value = transcript,
                onValueChange = {},
                readOnly = true,
                singleLine = false,
                maxLines = Int.MAX_VALUE,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                label = { Text("Transcript") }
            )

            Button(
                onClick = {
                    if (isRecording) {
                        viewModel.stopRecording()
                    } else {
                        // start path: clear + permission check + start
                        viewModel.clearTranscript()
                        if (ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            //Log.d("Dhruv", "About to start Recording")
                            viewModel.startRecording(context, selectedLanguage, translateEnabled, )
                        } else {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                },
                enabled = true  // always clickable
            ) {
                Text(if (isRecording) "Stop Recording" else "Start Recording")
            }

            // ──────────────────────────────────────────────────────────────
            // Language row with more horizontal padding and bolder label
            // ──────────────────────────────────────────────────────────────
            @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp), // increased side margin
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Language",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )

                ExposedDropdownMenuBox(
                    expanded = languageExpanded,
                    onExpandedChange = { languageExpanded = !languageExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedLanguage,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier
                            .menuAnchor()          // ✅ anchors the popup to this field
                            .widthIn(min = 160.dp),
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageExpanded)
                        },
                        label = null
                    )
                    ExposedDropdownMenu(
                        expanded = languageExpanded,
                        onDismissRequest = { languageExpanded = false }
                    ) {
                        languageOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    selectedLanguage = option
                                    languageExpanded = false
                                }
                            )
                        }
                    }
                }

//                Box {
//                    OutlinedTextField( // looks like a field, not a button
//                        value = selectedLanguage,
//                        onValueChange = {},
//                        readOnly = true,
//                        modifier = Modifier
//                            .widthIn(min = 120.dp)
//                            .clickable { languageExpanded = true },
//                        trailingIcon = {
//                            Icon(Icons.Default.ArrowDropDown, contentDescription = "Select Language")
//                        }
//                    )
//                    DropdownMenu(
//                        expanded = languageExpanded,
//                        onDismissRequest = { languageExpanded = false }
//                    ) {
//                        languageOptions.forEach { option ->
//                            DropdownMenuItem(
//                                text = { Text(option) },
//                                onClick = {
//                                    selectedLanguage = option
//                                    languageExpanded = false
//                                }
//                            )
//                        }
//                    }
//                }
            }

            // Model row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Model",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )

                Box {
                    OutlinedTextField(
                        value = selectedModel,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier
                            .widthIn(min = 120.dp)
                            .clickable { modelExpanded = true },
                        trailingIcon = {
                            Icon(Icons.Default.ArrowDropDown, contentDescription = "Select Model")
                        }
                    )
                    DropdownMenu(
                        expanded = modelExpanded,
                        onDismissRequest = { modelExpanded = false }
                    ) {
                        modelOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    selectedModel = option
                                    modelExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // Translate toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Translate to English",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Switch(
                    checked = translateEnabled,
                    onCheckedChange = { translateEnabled = it }
                )
            }

            // ─── Error display ──────────────────────
            errorMsg?.let {
                Text(it, color = Color.Red)
            }
        }
    }
}



