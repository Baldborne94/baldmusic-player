package io.github.baldborne94.baldmusic.core.library

/**
 * L'albero unico che vedono sia l'app sia Android Auto. Smista gli ID verso la
 * sorgente giusta e ricostruisce le code a partire da un brano scelto.
 */
class LibraryTree(
    private val sources: () -> List<MusicSource>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Cached(val at: Long, val nodes: List<LibraryNode>)

    // Android Auto chiede i figli a pagine: senza cache ogni pagina rifarebbe la query.
    private val cache = object : LinkedHashMap<String, Cached>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 64
    }

    fun invalidate() = synchronized(cache) { cache.clear() }

    private fun source(key: String) = sources().firstOrNull { it.key == key }

    fun rootNodes(): List<LibraryNode> = sources().map {
        LibraryNode(mediaId = it.key, title = it.title, kind = LibraryNode.Kind.SOURCE)
    }

    suspend fun children(mediaId: String): List<LibraryNode> {
        if (mediaId == MediaId.ROOT) return rootNodes()
        val id = MediaId.parse(mediaId) ?: return emptyList()
        val src = source(id.source) ?: return emptyList()
        if (!src.cacheable) return if (id.kind == null) src.sections() else src.children(id)
        synchronized(cache) { cache[mediaId] }?.let { if (clock() - it.at < CACHE_MS) return it.nodes }
        val nodes = if (id.kind == null) src.sections() else src.children(id)
        synchronized(cache) { cache[mediaId] = Cached(clock(), nodes) }
        return nodes
    }

    suspend fun node(mediaId: String): LibraryNode? {
        if (mediaId == MediaId.ROOT) return LibraryNode(MediaId.ROOT, "BaldMusic", LibraryNode.Kind.SOURCE)
        val id = MediaId.parse(mediaId) ?: return null
        val src = source(id.source) ?: return null
        if (id.isTrack) return src.track(id)
        if (id.kind == null) return rootNodes().firstOrNull { it.mediaId == mediaId }
        src.node(id)?.let { return it }
        // Un contenitore si trova tra i figli di chi l'ha elencato: basta il titolo
        // dall'elenco già in cache, senza una chiamata apposita.
        synchronized(cache) { cache.values.toList() }.forEach { c ->
            c.nodes.firstOrNull { it.mediaId == mediaId }?.let { return it }
        }
        return LibraryNode(mediaId, "", LibraryNode.Kind.SECTION)
    }

    /**
     * Coda da riprodurre quando si sceglie [mediaId]:
     * - un brano con contenitore → tutto il contenitore, partendo da quel brano;
     * - un brano senza contenitore → solo lui;
     * - un contenitore (album, playlist…) → tutti i suoi brani.
     */
    suspend fun queueFor(mediaId: String): Pair<List<LibraryNode>, Int> {
        val id = MediaId.parse(mediaId) ?: return emptyList<LibraryNode>() to 0
        if (!id.isTrack) return playableIn(mediaId) to 0
        val parent = id.parent
        if (parent != null) {
            val siblings = playableIn(parent.toString())
            val index = siblings.indexOfFirst { MediaId.parse(it.mediaId)?.id == id.id }
            if (index >= 0) return siblings to index
        }
        val single = source(id.source)?.track(id) ?: return emptyList<LibraryNode>() to 0
        return listOf(single) to 0
    }

    /** Tutti i brani riproducibili sotto un contenitore, completi di streamUri. */
    suspend fun playableIn(mediaId: String): List<LibraryNode> {
        val nodes = children(mediaId)
        val tracks = nodes.filter { it.playable }
        if (tracks.isNotEmpty()) return tracks.map { resolve(it) }.filterNotNull()
        // Un artista contiene album e la sezione dei singoli: si scende di un livello.
        val insideArtist = MediaId.parse(mediaId)?.kind == "artist"
        return nodes.filter { it.kind == LibraryNode.Kind.ALBUM || (insideArtist && it.kind == LibraryNode.Kind.SECTION) }
            .flatMap { album -> children(album.mediaId).filter { it.playable } }
            .mapNotNull { resolve(it) }
    }

    suspend fun resolve(node: LibraryNode): LibraryNode? =
        if (node.streamUri != null) node else MediaId.parse(node.mediaId)?.let { source(it.source)?.track(it) }

    suspend fun resolve(mediaId: String): LibraryNode? {
        val id = MediaId.parse(mediaId) ?: return null
        return if (id.isTrack) source(id.source)?.track(id) else null
    }

    suspend fun search(query: String): List<LibraryNode> =
        sources().flatMap { src -> runCatching { src.search(query) }.getOrDefault(emptyList()) }

    companion object {
        const val CACHE_MS = 2 * 60 * 1000L
    }
}
