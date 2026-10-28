package com.lagradost.cloudstream4.desktop.ui

import com.lagradost.cloudstream4.desktop.data.tr
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.mvvm.logError

/** A part of an episode that can be skipped, in milliseconds */
data class SkipStamp(val startMs: Long, val endMs: Long, val isIntro: Boolean) {
    val label: String get() = if (isIntro) tr("Skip intro") else tr("Skip credits")
}

/** Intro and credits times of anime episodes from AniSkip (api.aniskip.com), found by MyAnimeList id */
object AniSkip {
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Interval(val startTime: Double, val endTime: Double)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Result(val interval: Interval, val skipType: String)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Response(val found: Boolean = false, val results: List<Result> = emptyList())

    suspend fun stamps(malId: Int, episode: Int, lengthMs: Long): List<SkipStamp> = try {
        val types = listOf("op", "ed", "mixed-op", "mixed-ed").joinToString("&") { "types[]=$it" }
        val text = app.get("https://api.aniskip.com/v2/skip-times/$malId/$episode?$types&episodeLength=${lengthMs / 1000}").text
        mapper.readValue<Response>(text).results.map {
            SkipStamp((it.interval.startTime * 1000).toLong(), (it.interval.endTime * 1000).toLong(), it.skipType.endsWith("op"))
        }
    } catch (t: Throwable) {
        // Most episodes are simply not in the database
        logError(t)
        emptyList()
    }
}
