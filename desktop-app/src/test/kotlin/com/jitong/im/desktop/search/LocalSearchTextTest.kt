package com.jitong.im.desktop.search

import com.jitong.im.search.LocalSearchText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalSearchTextTest {
    @Test
    fun english_terms_are_case_insensitive_and_indexed_as_words() {
        val plan = LocalSearchText.plan("  HELLO  ")

        assertNotNull(plan)
        assertEquals(listOf("whello"), plan.terms)
        assertTrue(LocalSearchText.matches("Say hello to the team", "HELLO"))
        assertFalse(LocalSearchText.matches("Say goodbye", "HELLO"))
        assertTrue(LocalSearchText.matches("hello, world", "hello world"))
    }

    @Test
    fun continuous_chinese_query_uses_adjacent_bigrams() {
        val plan = LocalSearchText.plan("你好世界")

        assertNotNull(plan)
        assertEquals(
            listOf(
                "cjk100fog",
                "cjk100hod",
                "cjk100ffa",
                "cjk100n64",
                "cjk00fog00hod",
                "cjk00hod00ffa",
                "cjk00ffa00n64",
            ),
            plan.terms,
        )
        assertTrue(LocalSearchText.matches("请说你好世界。", "你好世界"))
        assertFalse(LocalSearchText.matches("请说你好，世界。", "你好世界"))
    }

    @Test
    fun one_chinese_character_uses_original_text_matching() {
        val plan = LocalSearchText.plan("你")

        assertNotNull(plan)
        assertEquals(LocalSearchText.QueryMode.SINGLE_CJK_CHARACTER, plan.mode)
        assertTrue(LocalSearchText.matches("你好", "你"))
        assertTrue(LocalSearchText.terms("A你B").any { it.startsWith("cjk1") })
    }
}
