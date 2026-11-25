package io.github.baldborne94.baldmusic.core.subsonic

import io.github.baldborne94.baldmusic.core.subsonic.ServerCollections.State

/**
 * Applica sul server le regole di [ServerCollections], come `syncCollections` dell'app web: con
 * la musica sul NAS le raccolte si allineano anche dal telefono (dopo «Aggiorna libreria» e a ogni
 * allineamento), senza aprire il sito. Lo stato ([State]) lo salva chi chiama, per server e utente.
 */
class ServerCollectionsSync(private val client: SubsonicClient, private val user: String) {

    data class Result(
        /** Da salvare per la volta dopo (null = come prima). */
        val state: State?,
        val created: Int = 0,
        val added: Int = 0,
        val followed: Int = 0,
        /** Navidrome manda percorsi finti: manca «Report Real Path» nel lettore del telefono. */
        val noRealPath: Boolean = false,
    ) {
        val changed get() = created + added + followed > 0
    }

    /** [songs]: tutti i brani del server appena letti, col percorso. */
    suspend fun run(songs: List<Song>, saved: State?): Result {
        val folders = ServerCollections.folders(songs)
        if (folders.isEmpty()) {
            return Result(saved, noRealPath = songs.isNotEmpty() && songs.none { s -> s.path?.let(ServerCollections::isRealPath) == true })
        }
        val all = client.playlists()
        val deleted = all.flatMap { ServerCollections.deletedInStore(it.comment).orEmpty() }.toSet()
        var playlists = all.filter { ServerCollections.deletedInStore(it.comment) == null }
        val known = LinkedHashMap(saved?.known.orEmpty())

        // Cartelle rinominate: la raccolta vecchia passa alla cartella nuova invece di restare accanto a una doppia.
        val orphans = playlists.filter { p -> mine(p) && ServerCollections.folderOf(p.comment)?.let { it !in folders } == true }
        var followed = 0
        if (orphans.isNotEmpty()) {
            val taken = playlists.mapNotNullTo(HashSet()) { ServerCollections.folderOf(it.comment) }
            val byId = songs.associateBy { it.id }
            val withSongs = orphans.map { p -> ServerCollections.Orphan(p.id, p.name, ServerCollections.folderOf(p.comment)!!, client.playlist(p.id).entry) }
            val follows = ServerCollections.follow(folders.mapValues { (_, ids) -> ids.mapNotNull(byId::get) }, withSongs, taken, deleted)
            for (f in follows) {
                client.updatePlaylist(f.playlistId, name = f.rename, comment = ServerCollections.comment(f.folder))
                if (f.newIds.isNotEmpty()) client.replacePlaylist(f.playlistId, null, f.songIds)
                // I brani della cartella li ha già: da qui in poi entrano solo quelli nuovi.
                known[f.folder] = folders.getValue(f.folder)
                known.remove(withSongs.first { it.id == f.playlistId }.folder)
            }
            val moved = follows.flatMap { it.newIds.entries }.associate { it.key to it.value }
            if (moved.isNotEmpty()) moveSongs(moved, follows.mapTo(HashSet()) { it.playlistId })
            if (follows.isNotEmpty()) playlists = client.playlists().filter { ServerCollections.deletedInStore(it.comment) == null }
            followed = follows.size
        }

        val plan = ServerCollections.plan(folders, playlists, State(known), deleted)
        for (c in plan.create) {
            val pl = client.createPlaylist(c.folder, c.songIds.take(PLAYLIST_CHUNK))
            client.updatePlaylist(pl.id, comment = ServerCollections.comment(c.folder), add = c.songIds.drop(PLAYLIST_CHUNK))
        }
        var added = 0
        for (a in plan.add) {
            val have = client.playlist(a.playlistId).entry.mapTo(HashSet()) { it.id }
            val fresh = a.songIds.filter { it !in have }
            client.updatePlaylist(a.playlistId, add = fresh)
            added += fresh.size
        }
        if (plan.newlyDeleted.isNotEmpty()) rememberDeleted(plan.newlyDeleted)
        return Result(plan.state, created = plan.create.size, added = added, followed = followed)
    }

    private fun mine(p: Playlist) = p.owner == null || p.owner == user

    /** File con un ID nuovo: le tue playlist e i «mi piace» del server passano al nuovo (come `moveSongs` nel web). */
    private suspend fun moveSongs(newIds: Map<String, String>, skip: Set<String>) {
        for (p in client.playlists()) {
            if (!mine(p) || p.id in skip || p.isAppData) continue
            val ids = client.playlist(p.id).entry.map { it.id }
            if (ids.any { it in newIds }) client.replacePlaylist(p.id, null, replaceCopies(ids, newIds))
        }
        val liked = client.starred().song.mapTo(HashSet()) { it.id }
        client.star(newIds.filter { (old, new) -> old in liked && new !in liked }.values.toSet(), true)
    }

    /** Aggiunge cartelle all'elenco delle raccolte eliminate su Navidrome (lo stesso del web). */
    private suspend fun rememberDeleted(add: List<String>) {
        val store = client.playlists().firstOrNull { mine(it) && it.isAppData }
        val before = store?.let { ServerCollections.deletedInStore(it.comment) }.orEmpty().toSet()
        val after = before + add
        if (after == before) return
        val id = store?.id ?: client.createPlaylist(ServerCollections.DELETED_STORE_NAME, emptyList()).id
        client.updatePlaylist(id, comment = ServerCollections.deletedStoreComment(after))
    }

    companion object {
        /** Ogni brano al suo ID nuovo, una volta sola, nello stesso ordine (come `replaceCopies` nel web). */
        fun replaceCopies(ids: List<String>, newIds: Map<String, String>): List<String> {
            val out = ArrayList<String>()
            val seen = HashSet<String>()
            for (id in ids) {
                val kept = newIds[id]
                if (kept == null) {
                    out += id
                    seen += id
                } else if (kept !in seen && kept !in ids) {
                    out += kept
                    seen += kept
                }
            }
            return out
        }
    }
}
