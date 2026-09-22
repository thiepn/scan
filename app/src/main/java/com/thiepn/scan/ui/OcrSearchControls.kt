package com.thiepn.scan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thiepn.scan.data.DocumentPageSearchHit
import com.thiepn.scan.data.OcrLayoutCodec
import com.thiepn.scan.data.OcrScript
import com.thiepn.scan.data.OcrSearchTerms
import com.thiepn.scan.data.PageEntity

@Composable
fun OcrScriptDialog(
    current: OcrScript,
    onDismiss: () -> Unit,
    onApply: (OcrScript) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("OCR language model") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "The selected model is stored per document. Changing it re-runs OCR for all active pages."
                )
                OcrScript.entries.forEach { script ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onApply(script) }
                            .padding(vertical = 4.dp)
                    ) {
                        RadioButton(
                            selected = script == current,
                            onClick = { onApply(script) }
                        )
                        Column(Modifier.padding(start = 6.dp)) {
                            Text(script.label)
                            if (script == OcrScript.LATIN) {
                                Text("English, German, French, Turkish and other Latin-script text")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun DocumentSearchDialog(
    query: String,
    hits: List<DocumentPageSearchHit>,
    searching: Boolean,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onOpenHit: (DocumentPageSearchHit) -> Unit
) {
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Find in document") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    placeholder = { Text("Search OCR text") },
                    supportingText = {
                        Text("Quotes = exact phrase · * = prefix")
                    }
                )

                when {
                    query.isBlank() -> Text("Enter a word, phrase, or prefix.")
                    searching -> Text("Searching…")
                    hits.isEmpty() -> Text("No matching pages.")
                    else -> {
                        Text(
                            "${hits.size} matching page${if (hits.size == 1) "" else "s"}",
                            fontWeight = FontWeight.SemiBold
                        )
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(hits, key = { it.pageId }) { hit ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpenHit(hit) }
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            "Page ${hit.pageNumber}",
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(highlightSnippet(hit.snippet))
                                        if (hit.matchingWords.isNotEmpty()) {
                                            val exact = hit.matchingWords
                                                .sortedBy { it.readingOrder }
                                                .joinToString(" ") { it.text }
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.End
                                            ) {
                                                TextButton(
                                                    onClick = {
                                                        clipboard.setText(AnnotatedString(exact))
                                                    }
                                                ) {
                                                    Icon(
                                                        Icons.Default.ContentCopy,
                                                        contentDescription = null
                                                    )
                                                    Text(" Copy exact match")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
fun HighlightedOcrText(
    page: PageEntity,
    query: String,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    val result = OcrLayoutCodec.decode(page.ocrLayout)
    val terms = OcrSearchTerms.matchingWords(result, query)
        .map { it.text }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }

    if (query.isBlank() || terms.isEmpty()) {
        Text(page.ocrText, modifier = modifier, style = style)
        return
    }

    val primaryContainer = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer
    val ranges = buildList {
        terms.forEach { term ->
            Regex(
                Regex.escape(term),
                setOf(RegexOption.IGNORE_CASE)
            ).findAll(page.ocrText).forEach { match ->
                add(match.range)
            }
        }
    }.sortedBy { it.first }

    val annotated = buildAnnotatedString {
        var cursor = 0
        ranges.forEach { range ->
            if (range.first < cursor) return@forEach
            if (range.first > cursor) {
                append(page.ocrText.substring(cursor, range.first))
            }
            pushStyle(
                SpanStyle(
                    background = primaryContainer,
                    color = onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold
                )
            )
            append(page.ocrText.substring(range.first, range.last + 1))
            pop()
            cursor = range.last + 1
        }
        if (cursor < page.ocrText.length) {
            append(page.ocrText.substring(cursor))
        }
    }

    Text(annotated, modifier = modifier, style = style)
}

@Composable
private fun highlightSnippet(snippet: String): AnnotatedString {
    val primaryContainer = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer

    return buildAnnotatedString {
        var highlighted = false
        val plain = StringBuilder()

        fun flush() {
            if (plain.isEmpty()) return
            if (highlighted) {
                pushStyle(
                    SpanStyle(
                        background = primaryContainer,
                        color = onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
            append(plain.toString())
            if (highlighted) pop()
            plain.clear()
        }

        snippet.forEach { char ->
            when (char) {
                '‹' -> {
                    flush()
                    highlighted = true
                }
                '›' -> {
                    flush()
                    highlighted = false
                }
                else -> plain.append(char)
            }
        }
        flush()
    }
}
