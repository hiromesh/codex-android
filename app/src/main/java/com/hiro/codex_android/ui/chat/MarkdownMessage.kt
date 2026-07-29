package com.hiro.codex_android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MarkdownText
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension

/** CommonMark 解析后的 Compose 渲染：正文、列表、引用、链接和代码块均保持结构。 */
@Composable
fun MarkdownMessage(markdown: String, modifier: Modifier = Modifier) {
    val parser = remember { Parser.builder().extensions(listOf(TablesExtension.create())).build() }
    val document = remember(markdown) { parser.parse(markdown) }
    SelectionContainer {
        // 外层消息气泡决定最大宽度；短回复不应被拉成整行。
        Column(modifier = modifier) {
            MarkdownBlocks(document)
        }
    }
}

@Composable
private fun MarkdownBlocks(parent: Node, compact: Boolean = false) {
    children(parent).forEach { block ->
        when (block) {
            is Paragraph -> MarkdownParagraph(block, compact)
            is Heading -> Text(
                text = markdownInline(block, MaterialTheme.colorScheme.surfaceVariant),
                style = when (block.level) {
                    1 -> MaterialTheme.typography.headlineSmall
                    2 -> MaterialTheme.typography.titleLarge
                    else -> MaterialTheme.typography.titleMedium
                },
                modifier = Modifier.padding(top = if (compact) 0.dp else 6.dp),
            )
            is FencedCodeBlock -> MarkdownCodeBlock(block.literal)
            is IndentedCodeBlock -> MarkdownCodeBlock(block.literal)
            is TableBlock -> MarkdownTable(block)
            is BlockQuote -> Row(Modifier.padding(vertical = if (compact) 0.dp else 4.dp)) {
                Spacer(Modifier.width(3.dp).background(MaterialTheme.colorScheme.outlineVariant))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) { MarkdownBlocks(block, compact = true) }
            }
            is BulletList -> MarkdownList(block, ordered = false)
            is OrderedList -> MarkdownList(block, ordered = true)
            is ThematicBreak -> HorizontalDivider(Modifier.padding(vertical = 10.dp))
            is HtmlBlock -> Text(block.literal, style = MaterialTheme.typography.bodyMedium)
            else -> MarkdownBlocks(block, compact)
        }
    }
}

/** GFM 表格：固定的易读列宽，窄屏可横向滚动而不会把文字压得不可读。 */
@Composable
private fun MarkdownTable(table: TableBlock) {
    val rows = children(table)
        .flatMap { section -> children(section).filterIsInstance<TableRow>() }
        .toList()
    if (rows.isEmpty()) return

    val scrollState = rememberScrollState()
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .padding(top = 8.dp, bottom = 4.dp)
            .fillMaxWidth(),
    ) {
        Column(Modifier.horizontalScroll(scrollState).padding(1.dp)) {
            rows.forEach { row ->
                val header = row.parent is TableHead
                val cells = children(row).filterIsInstance<TableCell>().toList()
                Row {
                    cells.forEach { cell ->
                        Box(
                            Modifier
                                .width(132.dp)
                                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                .background(
                                    if (header) MaterialTheme.colorScheme.surface else Color.Transparent,
                                )
                                .padding(horizontal = 9.dp, vertical = 7.dp),
                        ) {
                            Text(
                                text = markdownInline(cell, MaterialTheme.colorScheme.surface),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownParagraph(node: Paragraph, compact: Boolean) {
    Text(
        text = markdownInline(node, MaterialTheme.colorScheme.surfaceVariant),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = if (compact) 0.dp else 4.dp),
    )
}

@Composable
private fun MarkdownList(list: Node, ordered: Boolean) {
    var number = (list as? OrderedList)?.startNumber ?: 1
    Column(Modifier.padding(top = 4.dp)) {
        children(list).filterIsInstance<ListItem>().forEach { item ->
            Row(Modifier.fillMaxWidth()) {
                Text(
                    text = if (ordered) "${number++}." else "•",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.width(28.dp),
                )
                Column(Modifier.weight(1f)) { MarkdownBlocks(item, compact = true) }
            }
        }
    }
}

@Composable
private fun MarkdownCodeBlock(code: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp).fillMaxWidth(),
    ) {
        Text(
            text = code.trimEnd(),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun markdownInline(node: Node, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    fun appendNode(current: Node) {
        when (current) {
            is MarkdownText -> append(current.literal)
            is Code -> {
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground))
                append(current.literal)
                pop()
            }
            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendChildren(current, ::appendNode) }
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendChildren(current, ::appendNode) }
            is Link -> {
                pushStringAnnotation("url", current.destination)
                withStyle(SpanStyle(color = Color(0xFF5C8DFF), textDecoration = TextDecoration.Underline)) {
                    appendChildren(current, ::appendNode)
                }
                pop()
            }
            is Image -> appendChildren(current, ::appendNode)
            is SoftLineBreak, is HardLineBreak -> append('\n')
            is HtmlInline -> append(current.literal)
            else -> appendChildren(current, ::appendNode)
        }
    }
    appendChildren(node, ::appendNode)
}

private fun AnnotatedString.Builder.appendChildren(parent: Node, appendNode: (Node) -> Unit) {
    children(parent).forEach(appendNode)
}

private fun children(parent: Node): Sequence<Node> = sequence {
    var child = parent.firstChild
    while (child != null) {
        yield(child)
        child = child.next
    }
}
