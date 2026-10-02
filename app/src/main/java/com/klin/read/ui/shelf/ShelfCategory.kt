package com.klin.read.ui.shelf

import com.klin.read.data.BookEntity

/**
 * A shelf filter entry.
 *
 * The row always starts with 全部, then the built-in states, then whatever custom
 * categories the user has created. Every entry carries a live count so the chip
 * can show it without the screen re-deriving one per render.
 */
data class ShelfCategory(
    val key: String,
    val label: String,
    val count: Int
) {
    companion object {
        const val ALL = "__all__"
        const val UNREAD = "__unread__"
        const val READING = "__reading__"
        const val FINISHED = "__finished__"

        /**
         * The reading-state filters, in display order. Custom categories follow.
         *
         * [started] maps a book id to whether it has been opened at all, because
         * "未读" means never started -- not merely unfinished. That fact lives in
         * ReaderPreferences, not in the book row, so the caller supplies it.
         *
         * There is deliberately no "未分类" chip here. It used to be one of these,
         * but it answers a different question -- "has the user put this in a custom
         * category" rather than "has the user read this" -- and putting the two on
         * one row made the row read as a single confusing scale. A book is either
         * 未读, 在读 or 读完, always exactly one of the three, and those three plus
         * 全部 are the whole row. A custom category still gets its own chip when the
         * user creates one; uncategorised books are simply not in it.
         */
        fun builtIn(
            books: List<BookEntity>,
            started: (BookEntity) -> Boolean
        ): List<ShelfCategory> = listOf(
            ShelfCategory(ALL, "全部", books.size),
            ShelfCategory(
                UNREAD,
                "未读",
                books.count { !it.isFinished && !started(it) }
            ),
            ShelfCategory(
                READING,
                "在读",
                books.count { !it.isFinished && started(it) }
            ),
            ShelfCategory(FINISHED, "读完", books.count { it.isFinished })
        )

        /** Custom categories present on the shelf, with counts. */
        fun custom(books: List<BookEntity>): List<ShelfCategory> =
            books.mapNotNull { it.category?.takeIf { name -> name.isNotBlank() } }
                .groupingBy { it }
                .eachCount()
                .map { (name, count) -> ShelfCategory("cat:$name", name, count) }
                .sortedBy { it.label }
    }
}

/**
 * Applies [category] to [books], preserving the shelf's own ordering.
 *
 * The four built-in keys cover reading state; anything else is a custom category
 * name after the `cat:` prefix.
 */
fun List<BookEntity>.filterBy(
    category: ShelfCategory,
    started: (BookEntity) -> Boolean
): List<BookEntity> = when (category.key) {
    ShelfCategory.ALL -> this
    ShelfCategory.FINISHED -> filter { it.isFinished }
    ShelfCategory.UNREAD -> filter { !it.isFinished && !started(it) }
    ShelfCategory.READING -> filter { !it.isFinished && started(it) }
    else -> {
        val name = category.key.removePrefix("cat:")
        filter { it.category == name }
    }
}
