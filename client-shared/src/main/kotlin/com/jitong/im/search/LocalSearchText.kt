package com.jitong.im.search

import java.util.Locale

/**
 * Search indexing rules shared by the Android Room adapter and the desktop H2 adapter.
 *
 * The persisted terms deliberately use ASCII keys so that both SQLite FTS4 and H2
 * compare the same tokens. CJK runs are represented by adjacent encoded bigrams;
 * the original text is always checked after the indexed lookup.
 */
object LocalSearchText {
    enum class QueryMode {
        INDEXED,
        SINGLE_CJK_CHARACTER,
    }

    data class QueryPlan(
        val normalizedQuery: String,
        val mode: QueryMode,
        val terms: List<String>,
    ) {
        val ftsMatch: String
            get() = terms.joinToString(" AND ")
    }

    fun plan(query: String): QueryPlan? {
        val normalized = normalize(query)
        if (normalized.isEmpty()) return null

        val codePoints = normalized.codePoints().toArray()
        val isSingleCjkCharacter = codePoints.size == 1 && isCjk(codePoints[0])
        if (isSingleCjkCharacter) {
            return QueryPlan(
                normalizedQuery = normalized,
                mode = QueryMode.SINGLE_CJK_CHARACTER,
                terms = emptyList(),
            )
        }

        val terms = terms(normalized)
        if (terms.isEmpty()) return null
        return QueryPlan(
            normalizedQuery = normalized,
            mode = QueryMode.INDEXED,
            terms = terms,
        )
    }

    fun terms(text: String): List<String> {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return emptyList()

        val result = linkedSetOf<String>()
        val codePoints = normalized.codePoints().toArray()
        var index = 0
        while (index < codePoints.size) {
            val codePoint = codePoints[index]
            if (isCjk(codePoint)) {
                val start = index
                while (index < codePoints.size && isCjk(codePoints[index])) {
                    index++
                }
                val run = codePoints.copyOfRange(start, index)
                run.forEach { result += cjkCharacter(it) }
                if (run.size > 1) {
                    for (offset in 0 until run.size - 1) {
                        result += cjkBigram(run[offset], run[offset + 1])
                    }
                }
                continue
            }
            if (Character.isLetterOrDigit(codePoint)) {
                val start = index
                while (
                    index < codePoints.size &&
                    !isCjk(codePoints[index]) &&
                    Character.isLetterOrDigit(codePoints[index])
                ) {
                    index++
                }
                result += word(codePoints.copyOfRange(start, index))
                continue
            }
            index++
        }
        return result.toList()
    }

    fun matches(text: String, query: String): Boolean {
        val normalizedText = normalize(text)
        val normalizedQuery = normalize(query)
        return normalizedQuery.isNotEmpty() && normalizedText.contains(normalizedQuery)
    }

    fun normalize(value: String): String {
        val lower = value.lowercase(Locale.ROOT)
        val normalized = StringBuilder(lower.length)
        lower.codePoints().forEach { codePoint ->
            when {
                Character.isWhitespace(codePoint) -> normalized.append(' ')
                Character.isLetterOrDigit(codePoint) || isCjk(codePoint) ->
                    normalized.appendCodePoint(codePoint)
                else -> normalized.append(' ')
            }
        }
        return normalized.toString().trim().replace(Regex("\\s+"), " ")
    }

    private fun word(codePoints: IntArray): String {
        val value = String(codePoints, 0, codePoints.size)
        return "w$value"
    }

    private fun cjkBigram(first: Int, second: Int): String =
        "cjk${first.toString(36).padStart(5, '0')}${second.toString(36).padStart(5, '0')}"

    private fun cjkCharacter(codePoint: Int): String =
        "cjk1${codePoint.toString(36).padStart(5, '0')}"

    private fun isCjk(codePoint: Int): Boolean =
        codePoint in 0x3400..0x4DBF ||
            codePoint in 0x4E00..0x9FFF ||
            codePoint in 0xF900..0xFAFF ||
            codePoint in 0x20000..0x2FA1F
}
