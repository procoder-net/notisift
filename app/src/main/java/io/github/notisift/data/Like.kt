package io.github.notisift.data

/** SQL LIKE helpers for the DAO's `ESCAPE '\'` queries. */
object Like {
    /** Matches [query] anywhere, with `%`, `_` and `\` taken literally. */
    fun contains(query: String): String =
        "%" + query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
}
