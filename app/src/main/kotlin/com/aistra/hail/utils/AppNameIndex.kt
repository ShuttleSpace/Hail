package com.aistra.hail.utils

import net.sourceforge.pinyin4j.PinyinHelper

object AppNameIndex {
    fun firstLetter(name: CharSequence): Char {
        val char = name.firstOrNull { it.isLetterOrDigit() } ?: return '#'
        if (char in 'A'..'Z' || char in 'a'..'z') return char.uppercaseChar()
        if (char.code < 128) return '#'
        val pinyin = runCatching { PinyinHelper.toHanyuPinyinStringArray(char) }.getOrNull()
        val first = pinyin?.firstOrNull()?.firstOrNull()
        if (first != null && first.isLetter()) return first.uppercaseChar()
        return if (char.isLetter()) char.uppercaseChar() else '#'
    }

    /** 应用名称的首字（中文/数字/字母原样，用于「应用首字」索引模式）。 */
    fun firstChar(name: CharSequence): Char {
        val char = name.firstOrNull { it.isLetterOrDigit() } ?: return '#'
        return char.uppercaseChar()
    }
}