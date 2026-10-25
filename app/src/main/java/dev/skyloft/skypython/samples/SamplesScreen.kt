package dev.skyloft.skypython.samples

import dev.skyloft.skypython.ui.rememberMainScope
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.WavingHand
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyloft.skypython.R
import dev.skyloft.skypython.ui.theme.Chevron
import dev.skyloft.skypython.ui.theme.Hairline
import dev.skyloft.skypython.ui.theme.IconTile
import dev.skyloft.skypython.ui.theme.MutedText
import dev.skyloft.skypython.ui.theme.Orange
import dev.skyloft.skypython.ui.theme.SkyTopBar
import dev.skyloft.skypython.ui.theme.SlateLine
import dev.skyloft.skypython.ui.theme.SlatePanel
import dev.skyloft.skypython.ui.theme.TextBright
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lists the bundled samples, with a search (top bar 🔍) that filters as you type. Tapping one
 * copies it to `projectsRoot/samples/<name>` (keeping an existing, possibly edited copy) and passes
 * the file to [onOpen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SamplesScreen(projectsRoot: File, onOpen: (File) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberMainScope()
    var samples by remember { mutableStateOf<List<Sample>?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) {
        samples = withContext(Dispatchers.IO) { runCatching { context.bundledSamples() }.getOrDefault(emptyList()) }
    }
    val closeSearch = { searching = false; query = "" }
    BackHandler(enabled = searching, onBack = closeSearch)

    Scaffold(
        topBar = {
            Column {
                SkyTopBar(
                    title = stringResource(R.string.title_samples),
                    onBack = onBack,
                    divider = false,
                    actions = {
                        IconButton(onClick = { if (searching) closeSearch() else searching = true }) {
                            Icon(
                                if (searching) Icons.Outlined.Close else Icons.Outlined.Search,
                                contentDescription = stringResource(if (searching) R.string.action_clear_search else R.string.samples_search),
                            )
                        }
                    },
                )
                AnimatedVisibility(searching, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    SearchField(query, onChange = { query = it })
                }
                Hairline()
            }
        },
    ) { padding ->
        val all = samples
        if (all == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        val list = remember(all, query) { filterSamples(all, query) }
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.samples_no_match, query.trim()), color = MutedText)
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(vertical = 4.dp)) {
            items(list, key = { it.name }) { sample ->
                SampleRow(sample) {
                    scope.launch {
                        val file = withContext(Dispatchers.IO) {
                            runCatching { context.copyBundledSample(sample.name, projectsRoot) }
                        }
                        file.onSuccess(onOpen).onFailure {
                            Toast.makeText(context, context.getString(R.string.samples_open_failed, it.message ?: it.toString()), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }
}

/** "Loops and lists: for, while, …" → a short name over the description, an icon tile and a ›. */
@Composable
private fun SampleRow(sample: Sample, onClick: () -> Unit) {
    val head = sample.title.substringBefore(": ", sample.title)
    val detail = sample.title.substringAfter(": ", "").replaceFirstChar { it.uppercaseChar() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(sampleIcon(sample.name), size = 40.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(head, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                detail.ifEmpty { sample.name },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Chevron(Modifier.padding(start = 8.dp))
    }
}

/** A recognisable icon per bundled sample (by its file name), code brackets otherwise. */
private fun sampleIcon(name: String): ImageVector = when {
    "hello" in name -> Icons.Outlined.WavingHand
    "loop" in name -> Icons.Outlined.Loop
    "function" in name -> Icons.Outlined.Functions
    "class" in name -> Icons.Outlined.Category
    "file" in name -> Icons.AutoMirrored.Outlined.InsertDriveFile
    "json" in name -> Icons.Outlined.DataObject
    "math" in name || "random" in name -> Icons.Outlined.Calculate
    "guess" in name || "game" in name -> Icons.Outlined.SportsEsports
    else -> Icons.Outlined.Code
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val hint = stringResource(R.string.samples_search_hint)
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = TextBright, fontSize = 15.sp),
        cursorBrush = SolidColor(Orange),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
            .focusRequester(focus)
            .semantics { contentDescription = hint },
        decorationBox = { inner ->
            Row(
                Modifier
                    .background(SlatePanel, shape)
                    .border(1.dp, SlateLine, shape)
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = MutedText)
                Box(Modifier.padding(start = 10.dp).weight(1f)) {
                    if (query.isEmpty()) Text(hint, color = MutedText, fontSize = 15.sp)
                    inner()
                }
            }
        },
    )
}
