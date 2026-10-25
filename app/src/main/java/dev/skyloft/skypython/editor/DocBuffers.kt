package dev.skyloft.skypython.editor

/**
 * What the editor view holds per open document, kept outside the view so it survives the view:
 * the live text buffer [T] (Sora's `Content`, which carries the cursor and the undo history) and
 * the scroll position. Owned by [EditorViewModel], so leaving the editor for another screen,
 * a bottom-bar tab switch or a rotation loses none of it; a doc's entry goes when its tab closes.
 * Main thread only.
 */
class DocBuffers<T : Any> {

    /** Scroll offsets in pixels. */
    data class Scroll(val x: Int, val y: Int)

    private val buffers = HashMap<String, T>()
    private val scrolls = HashMap<String, Scroll>()

    val ids: Set<String> get() = buffers.keys.toSet()

    operator fun get(id: String): T? = buffers[id]

    /** [id]'s buffer, made by [create] the first time it is shown. */
    fun getOrPut(id: String, create: () -> T): T = buffers.getOrPut(id, create)

    fun saveScroll(id: String, x: Int, y: Int) {
        if (id in buffers) scrolls[id] = Scroll(x, y)
    }

    fun scrollOf(id: String): Scroll? = scrolls[id]

    /** Forgets every doc not in [openIds] (closed tabs). */
    fun retain(openIds: Set<String>) {
        buffers.keys.retainAll(openIds)
        scrolls.keys.retainAll(openIds)
    }

    fun release(id: String) {
        buffers.remove(id)
        scrolls.remove(id)
    }
}
