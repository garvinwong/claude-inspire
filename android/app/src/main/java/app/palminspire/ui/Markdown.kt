package app.palminspire.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

private val parser: Parser = Parser.builder().extensions(listOf(TablesExtension.create(), StrikethroughExtension.create())).build()

private fun Node.children(): List<Node> = generateSequence(firstChild) { it.next }.toList()

/** Claude 回复的 Markdown。引用块用浅底区分，不用左缘色条。 */
@Composable
fun Markdown(text: String, modifier: Modifier = Modifier) {
    val doc = remember(text) { parser.parse(text) }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { doc.children().forEach { Block(it) } }
    }
}

@Composable
private fun Block(n: Node, depth: Int = 0) {
    val p = LocalPalm.current
    val ty = MaterialTheme.typography
    when (n) {
        is Paragraph -> Text(inline(n), style = ty.bodyLarge, color = p.ink)
        is Heading -> Text(inline(n), color = p.ink, fontWeight = FontWeight.SemiBold,
            fontSize = when (n.level) { 1 -> 20.sp; 2 -> 18.sp; 3 -> 16.sp; else -> 15.sp })
        is BulletList, is OrderedList -> {
            var i = if (n is OrderedList) n.markerStartNumber ?: 1 else 0
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                n.children().filterIsInstance<ListItem>().forEach { item ->
                    Row {
                        Text(if (n is OrderedList) "${i++}." else "•", style = ty.bodyLarge, color = p.ink2,
                            modifier = Modifier.width(if (n is OrderedList) 24.dp else 16.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            item.children().forEach { Block(it, depth + 1) }
                        }
                    }
                }
            }
        }
        is FencedCodeBlock -> CodeBox(n.literal.trimEnd('\n'), n.info ?: "")
        is IndentedCodeBlock -> CodeBox(n.literal.trimEnd('\n'), "")
        is BlockQuote -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.surface2).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) { n.children().forEach { Block(it, depth + 1) } }
        is ThematicBreak -> androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
        is TableBlock -> Table(n)
        is HtmlBlock -> Text(n.literal.trim(), style = ty.bodyMedium, color = p.ink2)
        else -> n.children().forEach { Block(it, depth) }
    }
}

@Composable
fun CodeBox(code: String, lang: String) {
    val p = LocalPalm.current
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1500); copied = false } }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.code)) {
        // 右上角复制（评审意见：手机上长按选命令很难选准）
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 2.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(lang, style = MaterialTheme.typography.labelSmall, color = p.ink3, modifier = Modifier.weight(1f))
            androidx.compose.foundation.layout.Row(Modifier.clip(RoundedCornerShape(8.dp))
                .pressable { clip.setText(androidx.compose.ui.text.AnnotatedString(code)); copied = true }.padding(6.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Ico(if (copied) AppIcons.check else AppIcons.copy, 14.dp, if (copied) p.ok else p.ink3)
                if (copied) Text(" 已复制", style = MaterialTheme.typography.labelSmall, color = p.ok)
            }
        }
        Text(code, fontFamily = Mono, fontSize = 12.5.sp, lineHeight = 18.sp, color = p.ink,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 10.dp, end = 10.dp, bottom = 10.dp))
    }
}

@Composable
private fun Table(t: TableBlock) {
    val p = LocalPalm.current
    val rows = mutableListOf<Pair<Boolean, List<TableCell>>>()
    fun walk(n: Node, head: Boolean) {
        n.children().forEach { c ->
            when (c) {
                is TableRow -> rows += head to c.children().filterIsInstance<TableCell>()
                else -> walk(c, head || c is org.commonmark.ext.gfm.tables.TableHead)
            }
        }
    }
    walk(t, false)
    Column(Modifier.clip(RoundedCornerShape(10.dp)).background(p.surface2).horizontalScroll(rememberScrollState()).padding(4.dp)) {
        rows.forEach { (head, cells) ->
            Row {
                cells.forEach { c ->
                    Text(inline(c), style = MaterialTheme.typography.bodyMedium, color = p.ink,
                        fontWeight = if (head) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.widthIn(min = 64.dp, max = 220.dp).padding(horizontal = 8.dp, vertical = 5.dp))
                }
            }
        }
    }
}

@Composable
private fun inline(n: Node): AnnotatedString {
    val p = LocalPalm.current
    return buildAnnotatedString {
        fun walk(x: Node) {
            when (x) {
                is org.commonmark.node.Text -> append(x.literal)
                is Code -> withStyle(SpanStyle(fontFamily = Mono, background = p.code, fontSize = 13.5.sp)) { append(x.literal) }
                is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { x.children().forEach(::walk) }
                is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { x.children().forEach(::walk) }
                is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { x.children().forEach(::walk) }
                is Link -> withStyle(SpanStyle(color = p.info)) { x.children().forEach(::walk) }
                is SoftLineBreak -> append(' ')
                is HardLineBreak -> append('\n')
                is HtmlInline -> append(x.literal)
                else -> x.children().forEach(::walk)
            }
        }
        n.children().forEach(::walk)
    }
}
