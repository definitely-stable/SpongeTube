package io.github.definitelystable.spongetube

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SpongeTubeLabShell()
        }
    }
}

@Composable
private fun SpongeTubeLabShell() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 48.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "SpongeTube",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = "M0-A · Build foundation",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "Media playback and measurement arrive in later M0 work items.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
