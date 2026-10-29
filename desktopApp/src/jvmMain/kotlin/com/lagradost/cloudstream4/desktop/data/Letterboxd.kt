package com.lagradost.cloudstream4.desktop.data

import com.lagradost.cloudstream3.TvType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.zip.ZipFile

/**
 * Letterboxd's export (Settings → Data → Export your data, a zip of csv files) and its import format.
 * Imported ratings only feed the recommendations, the films are not on any extension.
 */
object Letterboxd {
    /** Reads ratings.csv, likes/films.csv and watched.csv from the export zip, or one of them on its own */
    fun read(file: File): List<ImportedRating> {
        val tables: Map<String, String> = if (file.extension.equals("zip", true)) {
            ZipFile(file).use { zip ->
                zip.entries().toList().filter { it.name.endsWith(".csv") && !it.name.startsWith("deleted/") }
                    .associate { it.name to zip.getInputStream(it).bufferedReader().readText() }
            }
        } else {
            mapOf(file.name to file.readText())
        }
        val films = LinkedHashMap<String, ImportedRating>()
        fun merge(rating: ImportedRating) {
            val previous = films[rating.key]
            films[rating.key] = rating.copy(stars = rating.stars ?: previous?.stars, liked = rating.liked || previous?.liked == true)
        }
        // Watched first, so ratings and likes complete them
        val order = listOf("watched.csv", "ratings.csv", "films.csv")
        tables.entries.sortedBy { (name, _) -> order.indexOfFirst { name.endsWith(it) }.let { if (it == -1) 99 else it } }
            .forEach { (name, text) ->
                val liked = name.endsWith("likes/films.csv")
                if (name.contains("watchlist") || name.contains("lists/")) return@forEach
                parse(text).forEach { row ->
                    val title = row["Name"]?.takeIf { it.isNotBlank() } ?: return@forEach
                    merge(
                        ImportedRating(
                            title,
                            row["Year"]?.toIntOrNull(),
                            row["Rating"]?.toFloatOrNull(),
                            liked = liked,
                            source = "Letterboxd",
                            movie = true,
                        )
                    )
                }
            }
        return films.values.toList()
    }

    /**
     * Writes the app's ratings and finished movies as a csv for Letterboxd's importer (letterboxd.com/import).
     * Shows are left out, Letterboxd only has films.
     */
    fun write(file: File): Int {
        val movieTypes = setOf(TvType.Movie, TvType.AnimeMovie, TvType.Documentary)
        val date = SimpleDateFormat("yyyy-MM-dd")
        val rows = LinkedHashMap<String, List<String>>()
        AppData.ratings.value.values.filter { it.media.type == null || it.media.type in movieTypes }.forEach {
            rows[it.media.url] = listOf(it.media.name, it.year?.toString() ?: "", it.stars?.toString() ?: "", date.format(Date(it.updatedAt)))
        }
        val positions = AppData.positions.value
        AppData.history.value.filter {
            it.media.type in movieTypes && it.media.url !in rows && it.episodeData?.let { data -> positions[data]?.isFinished } == true
        }.forEach { rows[it.media.url] = listOf(it.media.name, "", "", date.format(Date(it.updatedAt))) }
        file.writeText(
            (listOf(listOf("Title", "Year", "Rating", "WatchedDate")) + rows.values)
                .joinToString("\n") { row -> row.joinToString(",") { csvField(it) } } + "\n"
        )
        return rows.size
    }

    private fun csvField(value: String) =
        if (value.any { it == ',' || it == '"' || it == '\n' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    /** The rows of a csv with a header line, quoted fields can hold commas, quotes and line breaks */
    fun parse(text: String): List<Map<String, String>> {
        val records = mutableListOf<List<String>>()
        var field = StringBuilder()
        var record = mutableListOf<String>()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { record.add(field.toString()); field = StringBuilder() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    record.add(field.toString()); field = StringBuilder()
                    if (record.any { it.isNotEmpty() }) records.add(record)
                    record = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        record.add(field.toString())
        if (record.any { it.isNotEmpty() }) records.add(record)
        val header = records.firstOrNull()?.map { it.trim().removePrefix("﻿") } ?: return emptyList()
        return records.drop(1).map { row -> header.zip(row).toMap() }
    }
}
