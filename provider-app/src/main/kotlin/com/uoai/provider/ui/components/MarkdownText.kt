package com.uoai.provider.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class BulletItem(val marker: String, val text: String, val indent: Int = 0) : MdBlock
    data class CodeBlock(val language: String?, val code: String) : MdBlock
    data class BlockQuote(val text: String) : MdBlock
    data object Rule : MdBlock
}

/**
 * Lightweight, streaming-safe Jetpack Compose Markdown renderer with zero third-party dependencies.
 * Supports headings, paragraphs, bullet & numbered lists, fenced code blocks, blockquotes,
 * horizontal rules, and inline bold, italic, strikethrough, inline code, and links.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val primaryColor = MaterialTheme.colorScheme.primary
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant

    SelectionContainer(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEach { block ->
                when (block) {
                    is MdBlock.Heading -> {
                        val style = when (block.level) {
                            1 -> MaterialTheme.typography.titleLarge
                            2 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleSmall
                        }
                        Text(
                            text = parseInlineMarkdown(block.text, codeBg, primaryColor),
                            style = style.copy(fontWeight = FontWeight.SemiBold),
                            color = color,
                            modifier = Modifier.padding(top = if (block.level <= 2) 4.dp else 2.dp),
                        )
                    }
                    is MdBlock.Paragraph -> {
                        Text(
                            text = parseInlineMarkdown(block.text, codeBg, primaryColor),
                            style = MaterialTheme.typography.bodyMedium,
                            color = color,
                        )
                    }
                    is MdBlock.BulletItem -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = (block.indent * 12).dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = block.marker,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = primaryColor,
                            )
                            Text(
                                text = parseInlineMarkdown(block.text, codeBg, primaryColor),
                                style = MaterialTheme.typography.bodyMedium,
                                color = color,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    is MdBlock.BlockQuote -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min),
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .background(primaryColor.copy(alpha = 0.65f), RoundedCornerShape(2.dp)),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = parseInlineMarkdown(block.text, codeBg, primaryColor),
                                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                                color = mutedColor,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    is MdBlock.CodeBlock -> {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column {
                                if (!block.language.isNullOrBlank()) {
                                    Text(
                                        text = block.language,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = mutedColor,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    )
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                                Text(
                                    text = block.code,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = color,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(12.dp),
                                )
                            }
                        }
                    }
                    MdBlock.Rule -> {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

private val HEADING_REGEX = Regex("""^(#{1,6})\s+(.+)$""")
private val ORDERED_REGEX = Regex("""^(\s*)(\d+\.)\s+(.+)$""")
private val UNORDERED_REGEX = Regex("""^(\s*)[-*•]\s+(.+)$""")
private val RULE_REGEX = Regex("""^\s*([-*_])\1{2,}\s*$""")

private fun parseMarkdownBlocks(input: String): List<MdBlock> {
    if (input.isBlank()) return emptyList()
    val lines = input.replace("\r\n", "\n").split("\n")
    val out = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()

    fun flushParagraph() {
        val text = paragraph.toString().trim()
        if (text.isNotEmpty()) out += MdBlock.Paragraph(text)
        paragraph.clear()
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        // Fenced code block (handles unclosed fences gracefully during live streaming)
        if (trimmed.startsWith("```")) {
            flushParagraph()
            val lang = trimmed.removePrefix("```").trim().takeIf { it.isNotEmpty() }
            val codeBuf = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                if (codeBuf.isNotEmpty()) codeBuf.append('\n')
                codeBuf.append(lines[i])
                i++
            }
            out += MdBlock.CodeBlock(lang, codeBuf.toString())
            i++
            continue
        }

        if (trimmed.isEmpty()) {
            flushParagraph()
            i++
            continue
        }

        if (RULE_REGEX.matches(trimmed)) {
            flushParagraph()
            out += MdBlock.Rule
            i++
            continue
        }

        val heading = HEADING_REGEX.matchEntire(trimmed)
        if (heading != null) {
            flushParagraph()
            out += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim())
            i++
            continue
        }

        if (trimmed.startsWith(">")) {
            flushParagraph()
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                quoteLines += lines[i].trim().removePrefix(">").trimStart()
                i++
            }
            out += MdBlock.BlockQuote(quoteLines.joinToString("\n"))
            continue
        }

        val ordered = ORDERED_REGEX.matchEntire(line)
        if (ordered != null) {
            flushParagraph()
            val indent = (ordered.groupValues[1].length / 2).coerceAtMost(3)
            out += MdBlock.BulletItem(ordered.groupValues[2], ordered.groupValues[3].trim(), indent)
            i++
            continue
        }

        val unordered = UNORDERED_REGEX.matchEntire(line)
        if (unordered != null) {
            flushParagraph()
            val indent = (unordered.groupValues[1].length / 2).coerceAtMost(3)
            out += MdBlock.BulletItem("•", unordered.groupValues[2].trim(), indent)
            i++
            continue
        }

        if (paragraph.isNotEmpty()) paragraph.append('\n')
        paragraph.append(trimmed)
        i++
    }
    flushParagraph()
    return out
}

private fun parseInlineMarkdown(
    text: String,
    codeBackground: Color,
    linkColor: Color,
): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = text.length
    while (i < n) {
        // Inline code: `...`
        if (text[i] == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i + 1) {
                pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBackground,
                    ),
                )
                append(text.substring(i + 1, end))
                pop()
                i = end + 1
                continue
            }
        }

        // Bold + Italic: ***...***
        if (text.startsWith("***", i)) {
            val end = text.indexOf("***", i + 3)
            if (end > i + 3) {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic))
                append(parseInlineMarkdown(text.substring(i + 3, end), codeBackground, linkColor))
                pop()
                i = end + 3
                continue
            }
        }

        // Bold: **...** or __...__
        if (text.startsWith("**", i) || text.startsWith("__", i)) {
            val delim = text.substring(i, i + 2)
            val end = text.indexOf(delim, i + 2)
            if (end > i + 2) {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(parseInlineMarkdown(text.substring(i + 2, end), codeBackground, linkColor))
                pop()
                i = end + 2
                continue
            }
        }

        // Strikethrough: ~~...~~
        if (text.startsWith("~~", i)) {
            val end = text.indexOf("~~", i + 2)
            if (end > i + 2) {
                pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                append(parseInlineMarkdown(text.substring(i + 2, end), codeBackground, linkColor))
                pop()
                i = end + 2
                continue
            }
        }

        // Italic: *...*
        if (text[i] == '*' && (i + 1 < n && text[i + 1] != ' ' && text[i + 1] != '*')) {
            val end = text.indexOf('*', i + 1)
            if (end > i + 1 && text[end - 1] != ' ') {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                append(parseInlineMarkdown(text.substring(i + 1, end), codeBackground, linkColor))
                pop()
                i = end + 1
                continue
            }
        }

        // Link: [label](url)
        if (text[i] == '[') {
            val closeBracket = text.indexOf(']', i + 1)
            if (closeBracket > i + 1 && closeBracket + 1 < n && text[closeBracket + 1] == '(') {
                val closeParen = text.indexOf(')', closeBracket + 2)
                if (closeParen > closeBracket + 2) {
                    pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                    append(text.substring(i + 1, closeBracket))
                    pop()
                    i = closeParen + 1
                    continue
                }
            }
        }

        append(text[i])
        i++
    }
}
