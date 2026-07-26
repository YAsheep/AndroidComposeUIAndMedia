package com.example.devilvideostudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import com.example.devilvideostudio.theme.DevilVideoStudioTheme
import com.example.devilvideostudio.ui.editor.VideoEditorScreen

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    enableEdgeToEdge()
    setContent {
      DevilVideoStudioTheme {
        VideoEditorScreen(modifier = Modifier.fillMaxSize())
      }
    }
  }
}
