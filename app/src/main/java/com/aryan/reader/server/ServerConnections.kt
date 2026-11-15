package com.aryan.reader.server

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.aryan.reader.opds.OpdsCatalog
import com.aryan.reader.opds.OpdsRepository
import com.aryan.reader.shared.opds.SharedOpdsCatalogs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID

/*
 * "Connect server": a guided way to add a book server, modelled on Liseur's server setup.
 *
 * A connection is a server type + address + sign-in. Behind it sits a regular OPDS catalog (the
 * app's existing catalog list, browser and downloader), so a connected server shows up in the
 * Catalogs tab and its books are downloaded and imported like any other OPDS book. What this layer
 * adds is the part Liseur is good at: working out the right feed address from whatever the user
 * pasted, telling the user *why* a connection failed, and remembering whether it is connected.
 */

enum class ServerProvider(val id: String) {
    CALIBRE_WEB("calibre_web"),
    KOMGA("komga"),
    BOOKORBIT("bookorbit"),
    OPDS("opds");

    companion object {
        fun fromId(id: String?): ServerProvider = entries.firstOrNull { it.id == id } ?: OPDS
    }
}

enum class ServerStatus { UNKNOWN, CONNECTED, ERROR }

data class ServerConnection(
    val id: String,
    val provider: ServerProvider,
    val name: String,
    /** What the user typed (normalised), shown in the list. */
    val baseUrl: String,
    /** The OPDS feed that was found working. */
    val feedUrl: String,
    /** The entry in the OPDS catalog list that holds the sign-in. */
    val catalogId: String,
    val status: ServerStatus = ServerStatus.UNKNOWN,
    val checkedAtMillis: Long = 0L,
)

enum class ServerFailure { INVALID_URL, NETWORK, AUTH, SERVER, CERTIFICATE, NOT_OPDS, NOT_FOUND }

sealed class ServerConnectResult {
    data class Success(val connection: ServerConnection) : ServerConnectResult()
    data class Failure(val reason: ServerFailure) : ServerConnectResult()
}

// ------------------------------------------------------------------------------- address logic

// First path segments of the servers' own web pages: a pasted browser address like
// https://host/komga/series/123 means "the server lives at /komga".
private val WEB_PAGE_MARKERS = setOf(
    "series", "book", "books", "libraries", "library", "collections", "readlists", "search", "settings",
    "account", "login", "logout", "shelf", "shelves", "author", "authors", "admin", "browse", "reader",
    "read", "dashboard", "bookmarks", "download", "stats", "profile",
)

private const val MAX_CANDIDATES = 4

/**
 * The feed addresses to try, best guess first, from whatever the user typed or pasted.
 *
 * Servers behind a reverse proxy often live under a sub-path, so every prefix of the pasted path is
 * a candidate, longest first ("host/komga" before "host"). A plain OPDS connection uses the address
 * exactly as typed.
 */
internal fun serverFeedCandidates(provider: ServerProvider, rawAddress: String): List<String> {
    val url = SharedOpdsCatalogs.normalizeCatalogUrl(rawAddress).toHttpUrlOrNull() ?: return emptyList()
    if (provider == ServerProvider.OPDS) {
        return listOf(url.newBuilder().fragment(null).build().toString())
    }

    var segments = url.pathSegments.filter { it.isNotEmpty() }
    // Pasted from the web UI: cut at the first page marker.
    segments.indexOfFirst { it.lowercase() in WEB_PAGE_MARKERS }.takeIf { it >= 0 }?.let { segments = segments.take(it) }
    // Already an OPDS address: keep what is in front of it.
    segments.indexOfFirst { it.equals("opds", ignoreCase = true) }.takeIf { it >= 0 }?.let { segments = segments.take(it) }

    val suffixes: List<List<String>> = when (provider) {
        ServerProvider.CALIBRE_WEB -> listOf(listOf("opds", ""))
        ServerProvider.KOMGA -> listOf(listOf("opds", "v1.2", "catalog"))
        // BookOrbit documents an OPDS catalogue; its exact path is not verified here, so a few
        // usual places are tried. A wrong guess falls back to entering the OPDS address directly.
        ServerProvider.BOOKORBIT -> listOf(listOf("opds"), listOf("opds", "v1.2", "catalog"))
        ServerProvider.OPDS -> emptyList()
    }
    val prefixes = (segments.size downTo 0).map { segments.take(it) }.take(3)
    return prefixes
        .flatMap { prefix -> suffixes.map { suffix -> buildUrl(url, prefix + suffix) } }
        .distinct()
        .take(MAX_CANDIDATES)
}

private fun buildUrl(origin: HttpUrl, segments: List<String>): String {
    val builder = HttpUrl.Builder().scheme(origin.scheme).host(origin.host).port(origin.port)
    segments.forEach { builder.addPathSegment(it) }
    return builder.build().toString()
}

/** Why a request failed, in terms the user can act on (network, refused sign-in, server, certificate). */
internal fun classifyServerError(error: Throwable): ServerFailure {
    if (OpdsRepository.isCertificateError(error)) return ServerFailure.CERTIFICATE
    var cause: Throwable? = error
    while (cause != null) {
        if (cause is UnknownHostException || cause is ConnectException ||
            cause is SocketTimeoutException || cause is NoRouteToHostException
        ) {
            return ServerFailure.NETWORK
        }
        cause = cause.cause
    }
    val code = Regex("^HTTP (\\d{3})").find(error.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
    return when {
        code == 401 || code == 403 -> ServerFailure.AUTH
        code == 404 || code == 410 -> ServerFailure.NOT_FOUND
        code != null -> ServerFailure.SERVER
        error is IOException -> ServerFailure.NETWORK
        else -> ServerFailure.NOT_OPDS
    }
}

// ------------------------------------------------------------------------------------- storage

/** Remembers connections. Sign-in details live with the OPDS catalog, not here. */
class ServerConnectionStore private constructor(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("reader_server_connections", Context.MODE_PRIVATE)
    private val _connections = MutableStateFlow(load())
    val connections: StateFlow<List<ServerConnection>> = _connections.asStateFlow()

    private fun load(): List<ServerConnection> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val o = array.optJSONObject(index) ?: return@mapNotNull null
                ServerConnection(
                    id = o.optString("id").ifBlank { return@mapNotNull null },
                    provider = ServerProvider.fromId(o.optString("provider")),
                    name = o.optString("name"),
                    baseUrl = o.optString("baseUrl"),
                    feedUrl = o.optString("feedUrl"),
                    catalogId = o.optString("catalogId").ifBlank { return@mapNotNull null },
                    status = runCatching { ServerStatus.valueOf(o.optString("status")) }.getOrDefault(ServerStatus.UNKNOWN),
                    checkedAtMillis = o.optLong("checkedAt"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun persist(list: List<ServerConnection>) {
        val array = JSONArray()
        list.forEach { c ->
            array.put(
                JSONObject()
                    .put("id", c.id)
                    .put("provider", c.provider.id)
                    .put("name", c.name)
                    .put("baseUrl", c.baseUrl)
                    .put("feedUrl", c.feedUrl)
                    .put("catalogId", c.catalogId)
                    .put("status", c.status.name)
                    .put("checkedAt", c.checkedAtMillis),
            )
        }
        prefs.edit { putString(KEY, array.toString()) }
        _connections.value = list
    }

    @Synchronized
    fun upsert(connection: ServerConnection) {
        val current = _connections.value
        persist(if (current.any { it.id == connection.id }) current.map { if (it.id == connection.id) connection else it } else current + connection)
    }

    @Synchronized
    fun remove(id: String) = persist(_connections.value.filterNot { it.id == id })

    @Synchronized
    fun setStatus(id: String, status: ServerStatus, now: Long) {
        persist(_connections.value.map { if (it.id == id) it.copy(status = status, checkedAtMillis = now) else it })
    }

    companion object {
        private const val KEY = "connections_json"

        @Volatile
        private var instance: ServerConnectionStore? = null

        fun get(context: Context): ServerConnectionStore =
            instance ?: synchronized(this) {
                instance ?: ServerConnectionStore(context.applicationContext).also { instance = it }
            }
    }
}

/** Hands "open this server's library" from the server sheet to the Catalogs tab. */
object ServerOpenRequests {
    private val _catalogId = MutableStateFlow<String?>(null)
    val catalogId: StateFlow<String?> = _catalogId.asStateFlow()

    fun request(catalogId: String) {
        _catalogId.value = catalogId
    }

    fun consume() {
        _catalogId.value = null
    }
}

// -------------------------------------------------------------------------------------- connector

class ServerConnector(
    context: Context,
    private val store: ServerConnectionStore = ServerConnectionStore.get(context),
) {
    private val repository = OpdsRepository(context.applicationContext)

    /**
     * Finds the feed behind [address], signs in and saves the connection. Stops early when retrying
     * a different address cannot help (no network, refused sign-in, untrusted certificate).
     */
    suspend fun connect(
        provider: ServerProvider,
        name: String,
        address: String,
        username: String?,
        password: String?,
    ): ServerConnectResult = withContext(Dispatchers.IO) {
        val candidates = serverFeedCandidates(provider, address)
        if (candidates.isEmpty()) return@withContext ServerConnectResult.Failure(ServerFailure.INVALID_URL)

        val user = username?.trim()?.takeIf { it.isNotEmpty() }
        val pass = password?.takeIf { it.isNotEmpty() }
        var last = ServerFailure.NOT_FOUND
        for (candidate in candidates) {
            val result = repository.fetchFeed(candidate, user, pass)
            val feed = result.getOrNull()
            if (feed != null && (feed.entries.isNotEmpty() || feed.nextUrl != null)) {
                return@withContext ServerConnectResult.Success(save(provider, name, address, candidate, user, pass))
            }
            val reason = if (feed != null) ServerFailure.NOT_OPDS else classifyServerError(result.exceptionOrNull() ?: IOException())
            when (reason) {
                ServerFailure.AUTH, ServerFailure.NETWORK, ServerFailure.CERTIFICATE ->
                    return@withContext ServerConnectResult.Failure(reason)
                else -> last = reason
            }
        }
        ServerConnectResult.Failure(last)
    }

    private fun save(
        provider: ServerProvider,
        name: String,
        address: String,
        feedUrl: String,
        user: String?,
        pass: String?,
    ): ServerConnection {
        val baseUrl = SharedOpdsCatalogs.normalizeCatalogUrl(address)
        val existing = store.connections.value.firstOrNull { it.provider == provider && it.feedUrl == feedUrl }
        val catalogs = repository.loadCatalogs()
        val catalogId = existing?.catalogId?.takeIf { id -> catalogs.any { it.id == id } } ?: UUID.randomUUID().toString()
        val catalog = OpdsCatalog(id = catalogId, title = name, url = feedUrl, username = user, password = pass)
        repository.saveCatalogs(
            if (catalogs.any { it.id == catalogId }) catalogs.map { if (it.id == catalogId) catalog else it } else catalogs + catalog,
        )
        val connection = ServerConnection(
            id = existing?.id ?: UUID.randomUUID().toString(),
            provider = provider,
            name = name,
            baseUrl = baseUrl,
            feedUrl = feedUrl,
            catalogId = catalogId,
            status = ServerStatus.CONNECTED,
            checkedAtMillis = System.currentTimeMillis(),
        )
        store.upsert(connection)
        return connection
    }

    /** Signs out of a server: the connection and its catalog entry are removed. Local books stay. */
    fun disconnect(connection: ServerConnection) {
        repository.removeCatalog(connection.catalogId)
        store.remove(connection.id)
    }

    /** Re-checks one connection and records the result. Connections whose catalog was deleted elsewhere are dropped. */
    suspend fun check(connection: ServerConnection) = withContext(Dispatchers.IO) {
        val catalog = repository.loadCatalogs().firstOrNull { it.id == connection.catalogId }
        if (catalog == null) {
            store.remove(connection.id)
            return@withContext
        }
        val ok = try {
            repository.fetchFeed(catalog.url, catalog.username, catalog.password).isSuccess
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        store.setStatus(connection.id, if (ok) ServerStatus.CONNECTED else ServerStatus.ERROR, System.currentTimeMillis())
    }

    suspend fun checkAll() {
        store.connections.value.forEach { check(it) }
    }
}
