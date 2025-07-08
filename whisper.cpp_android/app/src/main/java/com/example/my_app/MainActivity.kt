//package com.example.my_app
//
//import android.Manifest
//import android.content.pm.PackageManager
//import android.os.Bundle
//import androidx.activity.ComponentActivity
//import androidx.activity.compose.rememberLauncherForActivityResult
//import androidx.activity.compose.setContent
//import androidx.activity.result.contract.ActivityResultContracts
//import androidx.compose.foundation.background
//import androidx.compose.foundation.layout.*
//import androidx.compose.material3.Button
//import androidx.compose.material3.Text
//import androidx.compose.runtime.*
//import androidx.compose.ui.Alignment
//import androidx.compose.ui.Modifier
//import androidx.compose.ui.graphics.Color
//import androidx.compose.ui.platform.LocalContext
//import androidx.compose.ui.unit.dp
//import androidx.core.content.ContextCompat
//import androidx.lifecycle.viewmodel.compose.viewModel
//
//class MainActivity : ComponentActivity() {
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        setContent {
//            val vm: WhisperViewModel = viewModel() // ViewModel survives rotations
//            RecordingAppUI(vm)
//        }
//    }
//}
//
//@Composable
//fun RecordingAppUI(viewModel: WhisperViewModel) {
//    val context = LocalContext.current
//
//    // ------- Observe ViewModel state -------
//    val isRecording by viewModel.isRecording.collectAsState()
//    val transcript   by viewModel.transcript.collectAsState()
//    val error        by viewModel.error.collectAsState()
//
//    // ------- Permission launcher -------
//    val micPermissionLauncher = rememberLauncherForActivityResult(
//        ActivityResultContracts.RequestPermission()
//    ) { granted ->
//        if (granted) {
//            viewModel.startRecording(context)
//        } else {
//            viewModel.setError("Microphone permission denied.")
//        }
//    }
//
//    val bg = if (isRecording) Color.LightGray else Color.Black
//
//    Box(
//        modifier = Modifier
//            .fillMaxSize()
//            .background(bg),
//        contentAlignment = Alignment.Center
//    ) {
//        Column(
//            verticalArrangement = Arrangement.spacedBy(16.dp),
//            horizontalAlignment = Alignment.CenterHorizontally
//        ) {
//            Button(
//                onClick = {
//                    if (ContextCompat.checkSelfPermission(
//                            context,
//                            Manifest.permission.RECORD_AUDIO
//                        ) == PackageManager.PERMISSION_GRANTED
//                    ) {
//                        viewModel.startRecording(context)
//                    } else {
//                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
//                    }
//                },
//                enabled = !isRecording
//            ) {
//                Text("Start Recording")
//            }
//
//            Button(
//                onClick = { viewModel.stopRecording() },
//                enabled = isRecording
//            ) {
//                Text("Stop Recording")
//            }
//
//            if (transcript.isNotEmpty()) {
//                Text(transcript)
//            }
//
//            error?.let {
//                Text(it, color = Color.Red)
//            }
//        }
//    }
//}

//package com.example.my_app
//
//import android.Manifest
//import android.content.pm.PackageManager
//import android.os.Bundle
//import androidx.activity.ComponentActivity
//import androidx.activity.compose.rememberLauncherForActivityResult
//import androidx.activity.compose.setContent
//import androidx.activity.result.contract.ActivityResultContracts
//import androidx.compose.foundation.background
//import androidx.compose.foundation.layout.*
//import androidx.compose.material3.Button
//import androidx.compose.material3.Text
//import androidx.compose.material3.OutlinedTextField
//import androidx.compose.runtime.*
//import androidx.compose.ui.Alignment
//import androidx.compose.ui.Modifier
//import androidx.compose.ui.graphics.Color
//import androidx.compose.ui.platform.LocalContext
//import androidx.compose.ui.text.input.TextFieldValue
//import androidx.compose.ui.unit.dp
//import androidx.core.content.ContextCompat
//import androidx.lifecycle.viewmodel.compose.viewModel
//
//class MainActivity : ComponentActivity() {
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        setContent {
//            val vm: WhisperViewModel = viewModel() // ViewModel survives rotations
//            RecordingAppUI(vm)
//        }
//    }
//}
//
//@Composable
//fun RecordingAppUI(viewModel: WhisperViewModel) {
//    val context = LocalContext.current
//
//    // ------- Observe ViewModel state -------
//    val isRecording by viewModel.isRecording.collectAsState()
//    val transcriptText by viewModel.transcript.collectAsState()
//    val errorMsg        by viewModel.error.collectAsState()
//
//    // Create a TextFieldValue for multiline support and cursor position maintenance
//    var transcript by remember { mutableStateOf(TextFieldValue(text = transcriptText)) }
//
//    // When transcriptText updates, update the TextFieldValue
//    LaunchedEffect(transcriptText) {
//        transcript = transcript.copy(text = transcriptText)
//    }
//
//    // ------- Permission launcher -------
//    val micPermissionLauncher = rememberLauncherForActivityResult(
//        ActivityResultContracts.RequestPermission()
//    ) { granted ->
//        if (granted) {
//            viewModel.startRecording(context)
//        } else {
//            viewModel.setError("Microphone permission denied.")
//        }
//    }
//
//    val bg = if (isRecording) Color.LightGray else Color.Black
//
//    Box(
//        modifier = Modifier
//            .fillMaxSize()
//            .background(bg),
//        contentAlignment = Alignment.Center
//    ) {
//        Column(
//            verticalArrangement = Arrangement.spacedBy(16.dp),
//            horizontalAlignment = Alignment.CenterHorizontally,
//            modifier = Modifier
//                .fillMaxWidth()
//                .padding(16.dp)
//        ) {
//            Button(
//                onClick = {
//                    if (ContextCompat.checkSelfPermission(
//                            context,
//                            Manifest.permission.RECORD_AUDIO
//                        ) == PackageManager.PERMISSION_GRANTED
//                    ) {
//                        viewModel.clearTranscript() // clear previous text
//                        viewModel.startRecording(context)
//                    } else {
//                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
//                    }
//                },
//                enabled = !isRecording
//            ) {
//                Text("Start Recording")
//            }
//
//            Button(
//                onClick = { viewModel.stopRecording() },
//                enabled = isRecording
//            ) {
//                Text("Stop Recording")
//            }
//
//            // ─── Multiline Transcript display ─────────────────
//            OutlinedTextField(
//                value = transcript,
//                onValueChange = {},
//                readOnly = true,
//                singleLine = false,
//                maxLines = Int.MAX_VALUE,
//                modifier = Modifier
//                    .fillMaxWidth()
//                    .height(200.dp),
//                label = { Text("Transcript") }
//            )
//
//            // ─── Error display ──────────────────────
//            errorMsg?.let {
//                Text(it, color = Color.Red)
//            }
//        }
//    }
//}


package com.example.my_app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
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
            val vm: WhisperViewModel = viewModel() // ViewModel survives rotations
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
    val errorMsg        by viewModel.error.collectAsState()
    val light = false

    // Create a TextFieldValue for multiline support and cursor position maintenance
    var transcript by remember { mutableStateOf(TextFieldValue(text = transcriptText)) }

    // When transcriptText updates, update the TextFieldValue
    LaunchedEffect(transcriptText) {
        transcript = transcript.copy(text = transcriptText)
    }

    // ------- Permission launcher -------
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startRecording(context)
        } else {
            viewModel.setError("Microphone permission denied.")
        }
    }

    //val bg = if (isRecording || light) Color.LightGray else Color.Black

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
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        viewModel.clearTranscript() // clear previous text
                        viewModel.startRecording(context)
                    } else {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
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

            // ─── Transcribe audio file button ─────────────────
            Button(
                onClick = { viewModel.transcribeFile(context, "jfk.wav") },
                enabled = !isRecording
            ) {
                Text("Transcribe File")
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




