package com.hmgapp.yeogiseotayo.engine

/** 한국어 조사: 앞 글자의 받침에 따라 고른다 ("노량진로로", "장승배기역으로", "1분 16초예요") */
object Josa {
    private fun jong(word: String): Int? {
        val c = word.trimEnd().lastOrNull() ?: return null
        if (c !in '가'..'힣') return null
        return (c - '가') % 28   // 0 = 받침 없음, 8 = ㄹ
    }
    /** 으로/로 */
    fun ro(word: String) = word + when (jong(word)) { null, 0, 8 -> "로"; else -> "으로" }
    /** 이에요/예요 */
    fun ieyo(word: String) = word + when (jong(word)) { null, 0 -> "예요"; else -> "이에요" }
}
