// Added by the CouchKit project (2026-10); licensed under GPL-3.0 like the rest of this app.
package io.github.jqssun.airplay.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val TermText = Color(0xFFCFCFCF)
private val Led = Color(0xFFE8EEF2)
private val Glow = Color(0xFF7FDBFF)

// connect intro: a plain black terminal replays the real handshake log, then types a configurable LED greeting
@Composable
fun TerminalIntro(
    logs: List<String>,
    ready: Boolean,
    welcome: String,
    onDone: () -> Unit,
) {
    // replay a long tail of the real receiver log so the screen floods for a couple of seconds
    val startIndex = remember { (logs.size - 150).coerceAtLeast(0) }
    val shown = remember { mutableStateListOf<String>() }
    var welcomeChars by remember { mutableIntStateOf(-1) } // -1 = still dumping logs
    var fading by remember { mutableStateOf(false) }
    val latestLogs by rememberUpdatedState(logs)
    val latestReady by rememberUpdatedState(ready)

    LaunchedEffect(Unit) {
        val startedAt = System.currentTimeMillis()
        var cursor = startIndex
        while (true) {
            val l = latestLogs
            if (cursor > l.size) cursor = l.size
            if (cursor < l.size) {
                // a few lines per frame: ~150 lines in well under a second
                repeat(minOf(4, l.size - cursor)) { shown.add(l[cursor++]) }
                while (shown.size > 80) shown.removeAt(0)
                delay(16)
                continue
            }
            if (latestReady && System.currentTimeMillis() - startedAt > 700) break
            delay(40)
        }
        delay(80)
        welcomeChars = 0
        while (welcomeChars < welcome.length) {
            welcomeChars++
            delay(15)
        }
        delay(450)
        fading = true
    }

    val alpha by animateFloatAsState(
        targetValue = if (fading) 0f else 1f,
        animationSpec = tween(120),
        label = "introFade",
        finishedListener = { if (fading) onDone() }
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(alpha)
            .background(Color.Black)
    ) {
        if (welcomeChars < 0) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.Bottom
            ) {
                (shown + "\u2588").takeLast(30).forEach {
                    Text(
                        it,
                        color = TermText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        } else {
            DotMatrixText(
                text = welcome.take(welcomeChars),
                totalChars = welcome.length,
                lit = true,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.8f)
                    .height(140.dp)
            )
        }
    }
}

// black screen that types one LED line, used for the goodbye when the sender leaves
@Composable
fun LedMessage(text: String) {
    var chars by remember { mutableIntStateOf(0) }
    LaunchedEffect(text) {
        while (chars < text.length) {
            chars++
            delay(15)
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        DotMatrixText(
            text = text.take(chars),
            totalChars = text.length,
            lit = true,
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.8f)
                .height(140.dp)
        )
    }
}

// dot-matrix LED lettering: every pixel is a short vertical bar with a faint glow
@Composable
private fun DotMatrixText(text: String, totalChars: Int, lit: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        if (!lit || text.isEmpty()) return@Canvas
        val cols = totalChars * 6 - 1
        val cell = minOf(size.width / cols, size.height / GLYPH_ROWS)
        // lay out for the full string so letters don't shift while typing
        val ox = (size.width - cols * cell) / 2
        val oy = (size.height - GLYPH_ROWS * cell) / 2
        val barW = cell * 0.5f
        val barH = cell * 0.84f
        text.forEachIndexed { ci, ch ->
            val rows = glyph(ch)
            rows.forEachIndexed { r, row ->
                row.forEachIndexed { c, px ->
                    if (px != '#') return@forEachIndexed
                    val cx = ox + (ci * 6 + c) * cell + cell / 2
                    val cy = oy + r * cell + cell / 2
                    drawRoundRect(
                        Glow.copy(alpha = 0.16f),
                        topLeft = Offset(cx - barW, cy - barH * 0.75f),
                        size = Size(barW * 2, barH * 1.5f),
                        cornerRadius = CornerRadius(barW)
                    )
                    drawRoundRect(
                        Led,
                        topLeft = Offset(cx - barW / 2, cy - barH / 2),
                        size = Size(barW, barH),
                        cornerRadius = CornerRadius(barW / 2)
                    )
                }
            }
        }
    }
}

// 5x7 base glyphs on an 11-row grid: rows 0-1 carry Turkish top marks, 9-10 cedillas
private const val GLYPH_ROWS = 11
private const val BLANK = "....."

private val BASE = mapOf(
    'A' to listOf(".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
    'B' to listOf("####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."),
    'C' to listOf(".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."),
    'D' to listOf("####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."),
    'E' to listOf("#####", "#....", "#....", "####.", "#....", "#....", "#####"),
    'F' to listOf("#####", "#....", "#....", "####.", "#....", "#....", "#...."),
    'G' to listOf(".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".####"),
    'H' to listOf("#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
    'I' to listOf(".###.", "..#..", "..#..", "..#..", "..#..", "..#..", ".###."),
    'J' to listOf("..###", "...#.", "...#.", "...#.", "...#.", "#..#.", ".##.."),
    'K' to listOf("#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"),
    'L' to listOf("#....", "#....", "#....", "#....", "#....", "#....", "#####"),
    'M' to listOf("#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"),
    'N' to listOf("#...#", "#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#"),
    'O' to listOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
    'P' to listOf("####.", "#...#", "#...#", "####.", "#....", "#....", "#...."),
    'Q' to listOf(".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#"),
    'R' to listOf("####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"),
    'S' to listOf(".####", "#....", "#....", ".###.", "....#", "....#", "####."),
    'T' to listOf("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."),
    'U' to listOf("#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
    'V' to listOf("#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.."),
    'W' to listOf("#...#", "#...#", "#...#", "#.#.#", "#.#.#", "#.#.#", ".#.#."),
    'X' to listOf("#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#"),
    'Y' to listOf("#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."),
    'Z' to listOf("#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"),
    '0' to listOf(".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."),
    '1' to listOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
    '2' to listOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
    '3' to listOf("####.", "....#", "....#", ".###.", "....#", "....#", "####."),
    '.' to listOf(BLANK, BLANK, BLANK, BLANK, BLANK, ".##..", ".##.."),
    '!' to listOf("..#..", "..#..", "..#..", "..#..", "..#..", BLANK, "..#.."),
    '-' to listOf(BLANK, BLANK, BLANK, ".###.", BLANK, BLANK, BLANK),
    ' ' to List(7) { BLANK },
)

// Turkish letters = base glyph + mark above or below
private val MARKS = mapOf(
    'Ç' to Triple('C', null, listOf("..#..", ".##..")),
    'Ş' to Triple('S', null, listOf("..#..", ".##..")),
    'Ö' to Triple('O', ".#.#.", null),
    'Ü' to Triple('U', ".#.#.", null),
    'Ğ' to Triple('G', ".###.", null),
    'İ' to Triple('I', "..#..", null),
)

private fun glyph(ch: Char): List<String> {
    val m = MARKS[ch]
    val base = BASE[m?.first ?: ch] ?: BASE.getValue(' ')
    val top = m?.second
    val bottom = m?.third
    return listOf(top ?: BLANK, BLANK) + base + (bottom ?: listOf(BLANK, BLANK))
}
