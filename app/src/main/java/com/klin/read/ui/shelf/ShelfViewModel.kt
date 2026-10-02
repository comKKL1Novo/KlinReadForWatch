package com.klin.read.ui.shelf

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.klin.read.data.BookEntity
import com.klin.read.data.ReaderDatabase
import com.klin.read.data.ReaderPreferences
import com.klin.read.data.ReadingStats
import com.klin.read.data.ReadingStatsStore
import com.klin.read.importer.BookImporter
import com.klin.read.reader.BookParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One-off messages surfaced to the user, then cleared. */
sealed interface ShelfMessage {
    data class ImportFailed(val reason: String) : ShelfMessage
    data class Notice(val text: String) : ShelfMessage
}

class ShelfViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = ReaderDatabase.get(app).bookDao()
    private val importer = BookImporter(app, dao)
    private val statsStore = ReadingStatsStore(app)
    private val prefs = ReaderPreferences(app)

    val books: StateFlow<List<BookEntity>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val stats: StateFlow<ReadingStats> = statsStore.stats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadingStats())

    private val _message = MutableStateFlow<ShelfMessage?>(null)
    val message: StateFlow<ShelfMessage?> = _message.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** Import progress, 0f..1f, so the shelf can show a bar. */
    private val _importProgress = MutableStateFlow(0f)
    val importProgress: StateFlow<Float> = _importProgress.asStateFlow()

    /** Currently selected filter chip. Defaults to 全部. */
    private val _selected = MutableStateFlow(ShelfCategory.ALL)
    val selected: StateFlow<String> = _selected.asStateFlow()

    /**
     * Last known reading position per book, kept live by the init block below.
     *
     * A [MutableStateFlow] rather than a plain map: the chips and progress bars are
     * derived from it, and a plain map mutating after the first composition would
     * leave the shelf showing stale counts until something else triggered a
     * recomposition.
     */
    private val positionCache = MutableStateFlow<Map<Long, ReaderPreferences.Position>>(emptyMap())

    /**
     * Filter chips with live counts.
     *
     * Counts are computed once per book-list or position change inside the flow,
     * not per recomposition: on a watch, walking the shelf on every frame is
     * exactly the kind of avoidable work that shows up as jank.
     *
     * [positionCache] is part of the combine because whether a book counts as
     * started depends on its saved position. Without it the chips were computed
     * against an empty cache and never recomputed, so a book stayed in "未读" no
     * matter how much of it was read -- reported as "读了没反应".
     */
    val categories: StateFlow<List<ShelfCategory>> = books
        .combine(dao.observeCategories()) { list, _ -> list }
        .combine(positionCache) { list, _ ->
            ShelfCategory.builtIn(list) { isStarted(it.id) } + ShelfCategory.custom(list)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Books matching the selected chip. */
    val visibleBooks: StateFlow<List<BookEntity>> = books
        .combine(_selected) { list, key -> list to key }
        .combine(positionCache) { (list, key), _ ->
            val category = (ShelfCategory.builtIn(list) { isStarted(it.id) } +
                ShelfCategory.custom(list))
                .firstOrNull { it.key == key }
                ?: ShelfCategory(ShelfCategory.ALL, "全部", list.size)
            list.filterBy(category) { isStarted(it.id) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun select(key: String) {
        _selected.value = key
    }

    /**
     * Whether [bookId] has ever been opened.
     *
     * True when there is ANY saved reading position, i.e. a non-zero character
     * offset OR a chapter past the first.
     *
     * This used to test `chapterIndex > 0` alone, which meant a reader who had
     * opened a book and read the whole of chapter one still showed as "未读":
     * chapter one is index 0, so the test never fired until they reached chapter
     * two. Reported as "读了没反应". The offset is the honest signal -- it is
     * written as soon as the reader scrolls or pages at all.
     */
    private fun isStarted(bookId: Long): Boolean {
        val saved = positionCache.value[bookId] ?: return false
        return saved.opened || saved.charOffset > 0 || saved.chapterIndex > 0
    }

    /**
     * How far through [book] the reader is, 0f..1f.
     *
     * Read synchronously from the stored chapter index rather than exposed as a
     * flow: the shelf only needs a rough bar, and a flow per book would mean one
     * subscription per row.
     */
    fun progressFor(book: BookEntity): Float {
        if (book.isFinished) return 1f

        val saved = positionCache.value[book.id]
        if (saved != null && book.charCount > 0 && saved.charOffset > 0) {
            return (saved.charOffset.toFloat() / book.charCount).coerceIn(0f, 1f)
        }

        if (book.charCount <= 0) return 0f

        /*
         * A book that has been opened but sits in chapter one reads as just started
         * rather than as 0%. Chapter one is offset 0 / index 0, so every signal the
         * shelf has says "the beginning" -- and a 0% bar on a book you are actively
         * reading looks like the same "nothing happened" bug as the chip counts.
         */
        val openedButAtStart = saved?.opened == true && saved.charOffset == 0 &&
            saved.chapterIndex == 0
        if (openedButAtStart) return 0.01f

        val chapter = saved?.chapterIndex ?: 0
        if (chapter <= 0) return 0f
        // Chapters are a coarse proxy when the offset is unavailable; the chapter
        // ratio alone returned 0f for anything still in chapter one, so the bar sat
        // empty no matter how much of that chapter had been read.
        return (chapter.toFloat() / maxOf(chapter + 1, chapterCountHint(book))).coerceIn(0f, 1f)
    }

    private fun chapterCountHint(book: BookEntity): Int =
        // Roughly one chapter per 8000 characters, which is typical for a novel.
        (book.charCount / 8000).coerceAtLeast(1)

    init {
        /*
         * Keep [positionCache] live, not a one-shot snapshot.
         *
         * Subscribes to the whole DataStore rather than to `books` plus a
         * `prefs.position(id)` flow per book. Two earlier attempts failed on the
         * phone build, and the same code was here:
         *
         *   1. `.first()` inside this init block took a snapshot at ViewModel
         *      creation, so reading a chapter and returning to the shelf showed the
         *      state from before the book was opened.
         *   2. Reading the position flows inside `books.collectLatest` meant a
         *      position written while the reader was on top of the shelf had no
         *      subscriber left to receive it.
         *
         * Subscribing to `dataStore.data` directly is the reliable form: it emits on
         * every preference write, whoever wrote it.
         */
        viewModelScope.launch {
            prefs.observePositions().collect { positions ->
                positionCache.value = positions
            }
        }
    }

    fun import(uri: Uri) {
        viewModelScope.launch {
            _importing.value = true
            _importProgress.value = 0f
            try {
                importer.import(uri) { fraction ->
                    _importProgress.value = fraction
                }
            } catch (e: BookParser.UnsupportedFormatException) {
                _message.value = ShelfMessage.ImportFailed(
                    "读不出这个文件，支持 ${BookParser.SUPPORTED_LABEL}"
                )
            } catch (e: Exception) {
                _message.value = ShelfMessage.ImportFailed(e.message ?: "无法读取该文件")
            } finally {
                _importing.value = false
                _importProgress.value = 0f
            }
        }
    }

    /**
     * Takes a persistable read grant on [uri].
     *
     * The picker grants access for the current process only; without persisting
     * it, every shelf entry would fail to open after a restart.
     */
    fun persistReadPermission(uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    fun remove(book: BookEntity) {
        viewModelScope.launch { dao.delete(book) }
    }

    /** Moves [book] into a custom category, or clears it when [name] is blank. */
    fun setCategory(book: BookEntity, name: String?) {
        viewModelScope.launch {
            dao.setCategory(book.id, name?.takeIf { it.isNotBlank() })
            _message.value = ShelfMessage.Notice(
                if (name.isNullOrBlank()) "已移出分类" else "已加入「$name」"
            )
        }
    }

    fun toggleFinished(book: BookEntity) {
        viewModelScope.launch {
            if (book.isFinished) {
                dao.clearFinished(book.id)
                _message.value = ShelfMessage.Notice("已标记为未读完")
            } else {
                dao.markFinished(book.id)
                _message.value = ShelfMessage.Notice("已标记为读完")
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
