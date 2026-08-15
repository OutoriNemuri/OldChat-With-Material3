package com.oldchat.material.feature.discover

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * 歌词行：时间（毫秒）+ 文本。
 */
data class LyricLine(
    val timeMs: Long,
    val text: String
)

/**
 * 歌词解析器，支持 LRC 和 TTML(XML) 两种格式。
 *
 * 基于内容探测而非文件后缀，因此 `.ttml`/`.xml` 的 XML 结构都能处理。
 */
object LyricsParser {

    /**
     * 解析歌词文本，返回按时间升序排列的歌词行列表。
     *
     * @param content 歌词原始文本
     * @param sourceUrl 来源 URL（用于辅助判断格式，可为空）
     */
    fun parse(content: String, sourceUrl: String? = null): List<LyricLine> {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return emptyList()

        // 尝试 XML / TTML 格式（TTML 本质是 XML 文档）
        if (trimmed.startsWith("<") || trimmed.startsWith("<?xml")) {
            val ttml = parseTtml(trimmed)
            if (ttml.isNotEmpty()) return ttml
        }

        // 尝试 LRC 格式
        val lrc = parseLrc(trimmed)
        if (lrc.isNotEmpty()) return lrc

        return emptyList()
    }

    /**
     * 解析 LRC 格式：`[mm:ss.xx]文本`，兼容一行多个时间标签。
     */
    private fun parseLrc(content: String): List<LyricLine> {
        val result = mutableListOf<LyricLine>()
        val lineRegex = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]([^\[]*)""")

        for (line in content.lines()) {
            val matches = lineRegex.findAll(line)
            var hasAny = false
            for (m in matches) {
                val min = m.groupValues[1].toLongOrNull() ?: continue
                val sec = m.groupValues[2].toLongOrNull() ?: continue
                val frac = m.groupValues[3].ifEmpty { "0" }.toLongOrNull() ?: 0L
                // 小数部分：1 位 = 百毫秒，2 位 = 十毫秒，3 位 = 毫秒
                val fracMs = when {
                    m.groupValues[3].length >= 3 -> frac / 1
                    m.groupValues[3].length == 2 -> frac * 10
                    m.groupValues[3].length == 1 -> frac * 100
                    else -> 0L
                }
                val timeMs = min * 60_000 + sec * 1_000 + fracMs
                val text = m.groupValues[4].trim()
                if (text.isNotEmpty()) {
                    result.add(LyricLine(timeMs, text))
                    hasAny = true
                }
            }
            if (!hasAny) {
                // 非时间标签行，忽略（元信息如 [ti:][ar:] 等）
            }
        }

        return result.sortedBy { it.timeMs }
    }

    /**
     * 解析 TTML / XML 歌词。
     *
     * 遍历所有 <p> 节点，取 begin 属性和文本内容。
     * 兼容 Apple Music / AMLL 风格的 TTML（含逐字 timing 时也取整行 begin）。
     */
    private fun parseTtml(content: String): List<LyricLine> {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(content))

            val result = mutableListOf<LyricLine>()
            var eventType = parser.eventType
            var currentBegin: Long = -1
            val textBuilder = StringBuilder()
            var inP = false

            // 解析时间 "mm:ss.mmm" 或 "hh:mm:ss.mmm" 为毫秒
            fun parseTimeToMs(t: String?): Long {
                if (t == null) return -1
                // 去掉可能的 "00:00:00.000" 中的小时
                val parts = t.trim().split(":")
                if (parts.isEmpty()) return -1
                return try {
                    val last = parts.last() // 秒.毫秒
                    val secParts = last.split(".")
                    val sec = secParts[0].toLong()
                    val ms = if (secParts.size > 1) {
                        val frac = secParts[1].padEnd(3, '0').take(3)
                        frac.toLong()
                    } else 0L
                    val min = if (parts.size >= 2) parts[parts.size - 2].toLong() else 0L
                    val hour = if (parts.size >= 3) parts[0].toLong() else 0L
                    hour * 3_600_000 + min * 60_000 + sec * 1_000 + ms
                } catch (e: Exception) {
                    -1
                }
            }

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val tagName = parser.name?.lowercase()
                        if (tagName == "p") {
                            inP = true
                            currentBegin = parseTimeToMs(parser.getAttributeValue(null, "begin"))
                            textBuilder.clear()
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inP) {
                            textBuilder.append(parser.text)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val tagName = parser.name?.lowercase()
                        if (tagName == "p" && inP) {
                            val text = textBuilder.toString().trim()
                            if (text.isNotEmpty() && currentBegin >= 0) {
                                result.add(LyricLine(currentBegin, text))
                            }
                            inP = false
                            currentBegin = -1
                        }
                    }
                }
                eventType = parser.next()
            }

            result.sortedBy { it.timeMs }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 根据当前播放位置（毫秒）找到应高亮显示的歌词行索引。
     * 返回 -1 表示当前还没有到任何歌词。
     */
    fun findCurrentLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var index = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= positionMs) {
                index = i
            } else {
                break
            }
        }
        return index
    }
}
