package dev.skyloft.skypython.samples

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import dev.skyloft.skypython.ui.theme.SkyTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import dev.skyloft.skypython.ui.theme.OutlinedIconTile
import androidx.compose.ui.unit.dp

/**
 * Lists the bundled samples. Tapping one copies it to `projectsRoot/samples/<name>`
 * (keeping an existing, possibly edited copy) and passes the file to [onOpen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SamplesScreen(projectsRoot: File, onOpen: (File) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var samples by remember { mutableStateOf<List<Sample>?>(null) }
    LaunchedEffect(Unit) {
        samples = withContext(Dispatchers.IO) { runCatching { context.bundledSamples() }.getOrDefault(emptyList()) }
    }

    Scaffold(
        topBar = {
            SkyTopBar(title = "Samples", onBack = onBack)
        },
    ) { padding ->
        val list = samples
        if (list == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(list, key = { it.name }) { sample ->
                // "Loops and lists: for, while, …" → a short bold name over the description.
                val head = sample.title.substringBefore(": ", sample.title)
                val detail = sample.title.substringAfter(": ", "").replaceFirstChar { it.uppercaseChar() }
                ListItem(
                    headlineContent = { Text(head, fontWeight = FontWeight.Medium) },
                    supportingContent = {
                        Text(
                            detail.ifEmpty { sample.name },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    leadingContent = {
                        OutlinedIconTile(Icons.Filled.Code, size = 40.dp)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {
                        scope.launch {
                            val file = withContext(Dispatchers.IO) {
                                runCatching { context.copyBundledSample(sample.name, projectsRoot) }
                            }
                            file.onSuccess(onOpen).onFailure {
                                Toast.makeText(context, "Could not open sample: ${it.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                )
            }
        }
    }
}
