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

//            Button(
//                onClick = {
//                    if (ContextCompat.checkSelfPermission(
//                            context, Manifest.permission.RECORD_AUDIO
//                        ) == PackageManager.PERMISSION_GRANTED
//                    ) {
//                        viewModel.clearTranscript()
//                        viewModel.startRecording(context)
//                    } else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
//                },
//                enabled = !isRecording
//            ) {
//                Text(
//                    if (isRecording) {
//                        "Stop Recording"
//                    } else {
//                        "Start Recording"
//                    }
//                )
//            }

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
                            Log.d("Dhruv", "About to start Recording")
                            viewModel.startRecording(context)
                        } else {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                },
                enabled = true  // always clickable
            ) {
                Text(if (isRecording) "Stop Recording" else "Start Recording")
            }


            // ─── Error display ──────────────────────
            errorMsg?.let {
                Text(it, color = Color.Red)
            }
        }
    }
}



