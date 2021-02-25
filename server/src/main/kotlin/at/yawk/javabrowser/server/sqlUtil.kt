package at.yawk.javabrowser.server

import org.intellij.lang.annotations.Language

@Language("sql", prefix = "select ")
fun escapeLike(@Language("sql", prefix = "select ") parameter: String) =
    "regexp_replace($parameter, '([\\\\%_])', '\\\\\\1', 'g')"
