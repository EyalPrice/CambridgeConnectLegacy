package io.github.eyalprice.ccl

/** Small, forgiving parsers for the XML snippets the Reciva/UuVol firmware returns. */
object Reciva {

    private val DOT = setOf(RegexOption.DOT_MATCHES_ALL)

    fun unescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&#39;", "'").replace("&amp;", "&")

    fun tag(xml: String?, name: String): String? {
        if (xml == null) return null
        val m = Regex("<$name(?:\\s[^>]*)?>(.*?)</$name>", DOT).find(xml) ?: return null
        return unescape(m.groupValues[1].trim())
    }

    fun attr(attrs: String, name: String): String? =
        Regex("""\b$name="([^"]*)"""").find(attrs)?.groupValues?.get(1)?.let(::unescape)

    /** Turns the stereo's sometimes double-escaped XML values into plain XML. */
    fun xmlValue(v: String?): String = if (v == null) "" else if (v.contains("&lt;")) unescape(v) else v

    // ---------------------------------------------------------------- presets

    data class Preset(val number: Int, val title: String, val description: String)

    fun presets(xml: String?): List<Preset> {
        val s = xmlValue(xml)
        return Regex("<preset([^>]*)>(.*?)</preset>", DOT).findAll(s).mapNotNull { m ->
            val n = attr(m.groupValues[1], "id")?.toIntOrNull() ?: return@mapNotNull null
            Preset(n, tag(m.groupValues[2], "title") ?: "", tag(m.groupValues[2], "description") ?: "")
        }.toList()
    }

    // ---------------------------------------------------------------- playback

    data class Playback(
        val state: String = "",
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val artUrl: String = "",
        val sourceName: String = "",
        val format: String = "",
        val spotifyActive: Boolean = false,
        val spotifyPlaying: Boolean = false,
        val spotifySource: String = "",
        val shuffle: Boolean = false,
        val repeat: Boolean = false,
        val durationSec: Int = 0,
    )

    fun playback(xml: String?): Playback {
        val s = xmlValue(xml)
        if (s.isBlank()) return Playback()
        val stream = tag(s, "stream")
        val entry = tag(s, "playlist-entry")
        val spotify = tag(s, "spotify-status")
        val fmtAttrs = Regex("<format([^>]*)>", DOT).find(s)?.groupValues?.get(1) ?: ""
        val codec = attr(fmtAttrs, "codec") ?: ""
        val bitrate = attr(fmtAttrs, "bit-rate")?.toIntOrNull() ?: 0
        val rate = attr(fmtAttrs, "sample-rate")?.toIntOrNull() ?: 0
        val format = buildString {
            if (codec.isNotEmpty()) append(codec)
            if (bitrate > 0) append(" · ").append(bitrate / 1000).append(" kbps")
            if (rate > 0) append(" · ").append(rate / 1000.0).append(" kHz")
        }.removePrefix(" · ")
        val title = tag(entry, "title")?.takeIf { it.isNotBlank() } ?: tag(stream, "title") ?: ""
        val art = tag(entry, "album-art-url")?.takeIf { it.isNotBlank() } ?: tag(stream, "album-art-url") ?: ""
        return Playback(
            state = tag(s, "state") ?: "",
            title = title,
            artist = tag(entry, "artist") ?: "",
            album = tag(entry, "album") ?: "",
            artUrl = art,
            sourceName = tag(stream, "source-name") ?: "",
            format = format,
            spotifyActive = tag(spotify, "active") == "1",
            spotifyPlaying = tag(spotify, "playing") == "1",
            spotifySource = tag(entry, "spotify-source") ?: "",
            shuffle = tag(spotify, "shuffle") == "1",
            repeat = tag(spotify, "repeat") == "1",
            durationSec = tag(entry, "duration")?.toIntOrNull() ?: 0,
        )
    }

    // ---------------------------------------------------------------- menus

    data class MenuItem(val id: Int, val type: String, val icon: String, val name: String)
    data class Menu(val id: Int, val name: String, val atTop: Boolean, val count: Int, val items: List<MenuItem>)

    fun menu(xml: String?): Menu? {
        val s = xmlValue(xml)
        val mm = Regex("<menu([^>]*)>", DOT).find(s) ?: return null
        val attrs = mm.groupValues[1]
        val name = tag(s, "name") ?: ""
        val countAttrs = Regex("<items([^>]*)>", DOT).find(s)?.groupValues?.get(1) ?: ""
        val items = Regex("<item([^>]*)>(.*?)</item>", DOT).findAll(s).mapNotNull { m ->
            val a = m.groupValues[1]
            val id = attr(a, "id")?.toIntOrNull() ?: return@mapNotNull null
            val inner = m.groupValues[2]
            val label = tag(inner, "name") ?: tag(inner, "title") ?: unescape(inner.replace(Regex("<[^>]+>"), "").trim())
            MenuItem(id, attr(a, "type") ?: "", attr(a, "icon") ?: "", label)
        }.toList()
        return Menu(
            id = attr(attrs, "id")?.toIntOrNull() ?: -1,
            name = name,
            atTop = attr(attrs, "atTop") == "1",
            count = attr(countAttrs, "count")?.toIntOrNull() ?: items.size,
            items = items,
        )
    }

    // ---------------------------------------------------------------- search results

    data class Station(val id: String, val name: String, val detail: String)

    /** Search replies are not documented; accept any element that has an id and a name or title. */
    fun stations(xml: String?): List<Station> {
        val s = xmlValue(xml)
        return Regex("<(station|item|result)([^>]*)>(.*?)</\\1>", DOT).findAll(s).mapNotNull { m ->
            val attrs = m.groupValues[2]
            val inner = m.groupValues[3]
            val id = attr(attrs, "id") ?: tag(inner, "id") ?: return@mapNotNull null
            val name = tag(inner, "name") ?: tag(inner, "title")
                ?: unescape(inner.replace(Regex("<[^>]+>"), " ").trim()).ifBlank { return@mapNotNull null }
            val detail = listOfNotNull(tag(inner, "location"), tag(inner, "genre"), tag(inner, "description"))
                .filter { it.isNotBlank() }.joinToString(" · ")
            Station(id, name, detail)
        }.toList()
    }

    /** Lists like <locations><location id="13">Australia</location>…, used for countries and genres. */
    fun idNames(xml: String?, tag: String): List<Pair<String, String>> {
        val s = xmlValue(xml)
        return Regex("<$tag([^>]*)>(.*?)</$tag>", DOT).findAll(s).mapNotNull { m ->
            val id = attr(m.groupValues[1], "id") ?: return@mapNotNull null
            val inner = m.groupValues[2]
            val name = tag(inner, "name") ?: unescape(inner.replace(Regex("<[^>]+>"), "").trim())
            if (name.isBlank()) null else id to name
        }.toList()
    }

    data class Track(val id: Int, val title: String, val artist: String)

    fun tracks(xml: String?): List<Track> {
        val s = xmlValue(xml)
        return Regex("<(track|entry|item)([^>]*)>(.*?)</\\1>", DOT).findAll(s).mapIndexedNotNull { i, m ->
            val attrs = m.groupValues[2]
            val inner = m.groupValues[3]
            val id = attr(attrs, "id")?.toIntOrNull() ?: tag(inner, "id")?.toIntOrNull() ?: i
            val title = tag(inner, "title") ?: tag(inner, "name") ?: return@mapIndexedNotNull null
            Track(id, title, tag(inner, "artist") ?: "")
        }.toList()
    }

    /** Well-known input numbers on Stream Magic / Reciva units, used when the stereo gives no name. */
    fun defaultInputName(n: Int): String = when (n) {
        0 -> "Internet radio"
        1, 10 -> "Media player"
        14 -> "Bluetooth"
        24 -> "Spotify"
        else -> "Input $n"
    }
}
