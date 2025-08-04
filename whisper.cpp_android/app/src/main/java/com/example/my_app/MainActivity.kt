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
            Button(
                onClick = {
                    if (ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        viewModel.clearTranscript()
                        viewModel.startRecording(context)
                    } else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                enabled = !isRecording
            ) {
                Text("Start Recording")
            }

            Button(
                onClick = { viewModel.stopRecording() },
                enabled = isRecording
            ) {
                Text("Stop Recording")
            }

            // Row: dropdown selector + transcribe file button
//            Row(
//                verticalAlignment = Alignment.CenterVertically,
//                horizontalArrangement = Arrangement.spacedBy(8.dp),
//                modifier = Modifier.fillMaxWidth()
//            ) {
//                // Stable dropdown implementation
//                Box(
//                    modifier = Modifier
//                        .weight(1f)
//                        .wrapContentSize(Alignment.TopStart)
//                ) {
//                    OutlinedTextField(
//                        value = selectedFile,
//                        onValueChange = {},
//                        readOnly = true,
//                        label = { Text("Audio File") },
//                        modifier = Modifier
//                            .fillMaxWidth()
//                            .clickable { expanded = !expanded }
//                    )
//                    DropdownMenu(
//                        expanded = expanded,
//                        onDismissRequest = { expanded = false }
//                    ) {
//                        assetFiles.forEach { file ->
//                            DropdownMenuItem(
//                                text = { Text(file) },
//                                onClick = {
//                                    selectedFile = file
//                                    expanded = false
//                                }
//                            )
//                        }
//                    }
//                }
//                Button(
//                    onClick = { viewModel.transcribeFile(context, selectedFile) },
//                    enabled = !isRecording && selectedFile.isNotEmpty()
//                ) {
//                    Text("Transcribe File")
//                }
//            }

            // Row: dropdown selector + transcribe file button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // make the BOX handle clicks instead of the TextField
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .wrapContentSize(Alignment.TopStart)
                        .clickable { expanded = !expanded }   // <-- moved here
                ) {
                    OutlinedTextField(
                        value = selectedFile,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Audio File") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        assetFiles.forEach { file ->
                            DropdownMenuItem(
                                text = { Text(file) },
                                onClick = {
                                    selectedFile = file
                                    expanded = false
                                }
                            )
                        }
                    }
                }
                Button(
                    onClick = { viewModel.transcribeFile(context, selectedFile) }, //jump off point
                    enabled = !isRecording && selectedFile.isNotEmpty()
                ) {
                    Text("Transcribe File")
                }
            }

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

            // ─── Error display ──────────────────────
            errorMsg?.let {
                Text(it, color = Color.Red)
            }
        }
    }
}



