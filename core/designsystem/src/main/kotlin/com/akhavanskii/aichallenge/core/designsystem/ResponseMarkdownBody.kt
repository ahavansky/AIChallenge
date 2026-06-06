package com.akhavanskii.aichallenge.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

@Composable
internal fun ResponseMarkdownBody(
    body: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(body) { parseMarkdownBlocks(body) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading ->
                    Text(
                        text = block.text.toInlineMarkdown(),
                        style =
                            when (block.level) {
                                1, 2 -> MaterialTheme.typography.titleMedium
                                else -> MaterialTheme.typography.titleSmall
                            },
                        color = color,
                        fontWeight = FontWeight.SemiBold,
                    )
                is MarkdownBlock.ListItem ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = block.marker,
                            style = MaterialTheme.typography.bodyLarge,
                            color = color,
                        )
                        Text(
                            text = block.text.toInlineMarkdown(),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            color = color,
                        )
                    }
                is MarkdownBlock.Paragraph ->
                    Text(
                        text = block.text.toInlineMarkdown(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = color,
                    )
                is MarkdownBlock.Table ->
                    MarkdownTable(
                        table = block,
                        color = color,
                    )
            }
        }
    }
}

@Composable
private fun MarkdownTable(
    table: MarkdownBlock.Table,
    color: Color,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        table.rows.forEachIndexed { rowIndex, row ->
            val rowTitle = row.firstOrNull()?.takeIf { it.isNotBlank() }
            if (rowTitle != null) {
                Text(
                    text = rowTitle.toInlineMarkdown(),
                    style = MaterialTheme.typography.titleSmall,
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                table.headers.drop(1).forEachIndexed { index, header ->
                    val value = row.getOrNull(index + 1)?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
                    Text(
                        text =
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                                    append(header.stripInlineMarkdown())
                                }
                                append(": ")
                                appendInlineMarkdown(value)
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        color = color,
                    )
                }
            }

            if (rowIndex < table.rows.lastIndex) {
                HorizontalDivider(modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

private sealed interface MarkdownBlock {
    data class Heading(
        val level: Int,
        val text: String,
    ) : MarkdownBlock

    data class ListItem(
        val marker: String,
        val text: String,
    ) : MarkdownBlock

    data class Paragraph(
        val text: String,
    ) : MarkdownBlock

    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>,
    ) : MarkdownBlock
}

private fun parseMarkdownBlocks(body: String): List<MarkdownBlock> {
    val lines = body.lines()
    val blocks = mutableListOf<MarkdownBlock>()
    var index = 0

    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()
        when {
            trimmed.isBlank() -> index += 1
            line.isMarkdownTableLine() -> {
                val tableLines = mutableListOf<String>()
                while (index < lines.size && lines[index].isMarkdownTableLine()) {
                    tableLines += lines[index]
                    index += 1
                }
                blocks += tableLines.toMarkdownTableBlock()
            }
            trimmed.startsWith("#") -> {
                val heading = trimmed.toHeadingBlock()
                if (heading != null) {
                    blocks += heading
                    index += 1
                } else {
                    blocks += MarkdownBlock.Paragraph(trimmed)
                    index += 1
                }
            }
            trimmed.toListItemBlock() != null -> {
                blocks += trimmed.toListItemBlock()!!
                index += 1
            }
            else -> {
                val paragraphLines = mutableListOf<String>()
                while (
                    index < lines.size &&
                    lines[index].isNotBlank() &&
                    !lines[index].isMarkdownTableLine() &&
                    lines[index].trim().toHeadingBlock() == null &&
                    lines[index].trim().toListItemBlock() == null
                ) {
                    paragraphLines += lines[index].trim()
                    index += 1
                }
                blocks += MarkdownBlock.Paragraph(paragraphLines.joinToString(separator = "\n"))
            }
        }
    }

    return blocks.ifEmpty { listOf(MarkdownBlock.Paragraph(body)) }
}

private fun String.toHeadingBlock(): MarkdownBlock.Heading? {
    val markerLength = takeWhile { it == '#' }.length
    if (markerLength !in 1..6 || getOrNull(markerLength) != ' ') {
        return null
    }
    return MarkdownBlock.Heading(
        level = markerLength,
        text = drop(markerLength).trim(),
    )
}

private fun String.toListItemBlock(): MarkdownBlock.ListItem? {
    val unordered = Regex("^[-*]\\s+(.+)$").matchEntire(this)
    if (unordered != null) {
        return MarkdownBlock.ListItem(marker = "•", text = unordered.groupValues[1])
    }

    val ordered = Regex("^(\\d+)\\.\\s+(.+)$").matchEntire(this)
    if (ordered != null) {
        return MarkdownBlock.ListItem(marker = "${ordered.groupValues[1]}.", text = ordered.groupValues[2])
    }

    return null
}

private fun String.isMarkdownTableLine(): Boolean {
    val trimmed = trim()
    return trimmed.startsWith("|") && trimmed.count { it == '|' } >= 2
}

private fun List<String>.toMarkdownTableBlock(): MarkdownBlock {
    val rows = map { it.toMarkdownTableCells() }.filter { it.size >= 2 }
    if (rows.isEmpty()) {
        return MarkdownBlock.Paragraph(joinToString(separator = "\n"))
    }

    val hasSeparator = rows.size > 1 && rows[1].isMarkdownSeparatorRow()
    val headers = rows.first()
    val dataRows = if (hasSeparator) rows.drop(2) else rows.drop(1)

    return if (dataRows.isEmpty()) {
        MarkdownBlock.Paragraph(joinToString(separator = "\n"))
    } else {
        MarkdownBlock.Table(
            headers = headers,
            rows = dataRows,
        )
    }
}

private fun String.toMarkdownTableCells(): List<String> =
    trim()
        .removePrefix("|")
        .removeSuffix("|")
        .split("|")
        .map { it.trim() }

private fun List<String>.isMarkdownSeparatorRow(): Boolean =
    all { cell ->
        val normalized = cell.replace(":", "").trim()
        normalized.isNotEmpty() && normalized.all { it == '-' }
    }

private fun String.toInlineMarkdown(): AnnotatedString =
    buildAnnotatedString {
        appendInlineMarkdown(this@toInlineMarkdown)
    }

private fun AnnotatedString.Builder.appendInlineMarkdown(text: String) {
    var index = 0
    while (index < text.length) {
        val start = text.indexOf("**", startIndex = index)
        if (start == -1) {
            append(text.substring(index))
            return
        }
        val end = text.indexOf("**", startIndex = start + 2)
        if (end == -1) {
            append(text.substring(index))
            return
        }

        append(text.substring(index, start))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(text.substring(start + 2, end))
        }
        index = end + 2
    }
}

private fun String.stripInlineMarkdown(): String = replace("**", "")
