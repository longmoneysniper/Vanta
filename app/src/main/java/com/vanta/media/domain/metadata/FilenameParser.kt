package com.vanta.media.domain.metadata

data class ParsedFilename(
    val cleanTitle: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val isTvEpisode: Boolean = season != null && episode != null
)

object FilenameParser {
    private val tvRegex = Regex("""(?i)^(.+?)[.\s_-]+[Ss](\d{1,2})[Ee](\d{1,3})""")
    private val movieYearRegex = Regex("""(?i)^(.+?)[.\s_-]+\(?((?:19|20)\d{2})\)?""")

    fun parse(rawFilename: String): ParsedFilename {
        val base = rawFilename.substringAfterLast('/').substringBeforeLast('.')

        tvRegex.find(base)?.let { match ->
            val rawTitle = match.groupValues[1].replace('.', ' ').replace('_', ' ').trim()
            val season = match.groupValues[2].toIntOrNull()
            val episode = match.groupValues[3].toIntOrNull()
            return ParsedFilename(
                cleanTitle = rawTitle,
                season = season,
                episode = episode,
                isTvEpisode = true
            )
        }

        movieYearRegex.find(base)?.let { match ->
            val rawTitle = match.groupValues[1].replace('.', ' ').replace('_', ' ').trim()
            val year = match.groupValues[2].toIntOrNull()
            return ParsedFilename(
                cleanTitle = rawTitle,
                year = year,
                isTvEpisode = false
            )
        }

        return ParsedFilename(
            cleanTitle = base.replace('.', ' ').replace('_', ' ').trim()
        )
    }
}
