package io.github.eyalprice.ccl

import java.net.HttpURLConnection
import java.net.URL

/**
 * Pro-mode test for custom radio stations: finds the stereo's address and web page, looks for any
 * command that reports or accepts a stream URL, and tries playing a URL the user types in.
 */
object StreamTest {
    /** Never offered for a URL test, whatever their arguments. */
    private val danger = listOf("upgrade", "firmware", "update", "reset", "factory", "delete", "erase", "wipe", "reboot", "power")

    private val urlWords = listOf("url", "uri", "stream", "station", "custom", "user", "favourite", "favorite", "mystation")

    fun isDangerous(name: String) = danger.any { name.lowercase().contains(it) }

    /** Commands whose name or arguments mention URLs, stations or streams. */
    fun related(d: Upnp.Device): List<Pair<Upnp.Service, Upnp.Action>> =
        d.services.flatMap { s -> s.actions.map { s to it } }.filter { (_, a) ->
            val n = a.name.lowercase()
            urlWords.any { n.contains(it) } || a.args.any { arg -> arg.name.lowercase().let { it.contains("url") || it.contains("uri") } }
        }

    /** Commands that take a URL argument and could start playback (user picks one to try). */
    fun playCandidates(d: Upnp.Device): List<Pair<Upnp.Service, Upnp.Action>> =
        related(d).filter { (_, a) ->
            !isDangerous(a.name) && a.inArgs().any { it.name.lowercase().let { n -> n.contains("url") || n.contains("uri") } }
        }

    fun fetch(url: String): String = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 5000; c.instanceFollowRedirects = true
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.trim() ?: ""
        val links = Regex("href\\s*=\\s*[\"']([^\"']+)", RegexOption.IGNORE_CASE).findAll(body).map { it.groupValues[1] }.distinct().take(30).toList()
        val forms = Regex("<form[^>]*action\\s*=\\s*[\"']([^\"']*)", RegexOption.IGNORE_CASE).findAll(body).map { it.groupValues[1] }.distinct().toList()
        val inputs = Regex("<input[^>]*name\\s*=\\s*[\"']([^\"']+)", RegexOption.IGNORE_CASE).findAll(body).map { it.groupValues[1] }.distinct().toList()
        buildString {
            append("HTTP $code · ${body.length} bytes")
            if (title.isNotBlank()) append("\nTitle: $title")
            if (links.isNotEmpty()) append("\nLinks: " + links.joinToString(", "))
            if (forms.isNotEmpty()) append("\nForms: " + forms.joinToString(", "))
            if (inputs.isNotEmpty()) append("\nForm fields: " + inputs.joinToString(", "))
            val text = body.replace(Regex("<script.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
                .replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
            if (text.isNotBlank()) append("\nText: " + text.take(600))
        }
    } catch (e: Exception) {
        "No answer (${e.javaClass.simpleName}: ${e.message ?: ""})"
    }

    fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Minimal DIDL-Lite description so a UPnP player shows the station name. */
    fun didl(name: String, url: String) =
        "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"1\" parentID=\"0\" restricted=\"1\">" +
            "<dc:title>${xml(name)}</dc:title><upnp:class>object.item.audioItem.audioBroadcast</upnp:class>" +
            "<res protocolInfo=\"http-get:*:*:*\">${xml(url)}</res></item></DIDL-Lite>"
}
