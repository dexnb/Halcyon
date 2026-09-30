package com.ella.music.ui.home

import com.ella.music.data.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import org.junit.Assert.*
import org.junit.Test

class LibraryAudioFilterPipelineTest {
    private fun song(id: Long, format: String) = Song(id, "song$id", "artist", "album", 1, 1000, "/$id.$format", "$id.$format")
    @Test fun formatSelectionChangesActualSongsAndResetRestoresAll() = runBlocking {
        val songs = listOf(song(1, "mp3"), song(2, "flac"), song(3, "mp3"))
        val results = filterLibraryAudio(songs, LibraryAudioFilter(formats = setOf("FLAC"))) { error("Known format-only filters must not block on disk metadata") }.toList()
        assertTrue(results.first().songs.isEmpty())
        assertEquals(listOf(2L), results.last().songs.map { it.id })
        assertTrue(results.last().complete)
        assertEquals(songs, filterLibraryAudio(songs, LibraryAudioFilter()) { error("Reset must not read metadata") }.toList().single().songs)
    }
    @Test fun qualityAndFormatAndHrSubgroupsFilterRealCollection() = runBlocking {
        val songs = listOf(song(1, "flac"), song(2, "flac"), song(3, "wav"), song(4, "mp3"))
        val infos = mapOf(1L to AudioInfo("FLAC", sampleRate=48000, bitDepth=24),
            2L to AudioInfo("FLAC", sampleRate=96000, bitDepth=24),
            3L to AudioInfo("WAV", sampleRate=48000, bitDepth=24))
        val filter = LibraryAudioFilter(setOf("HR"), setOf("FLAC"), setOf(24), setOf(48000))
        val result = filterLibraryAudio(songs, filter) { infos.getValue(it.id) }.toList().last()
        assertEquals(listOf(1L), result.songs.map { it.id })
        assertEquals(4, result.checked)
    }
    @Test fun m4aContainerUsesActualCodecRatherThanFilename() = runBlocking {
        val songs = listOf(song(1, "m4a"), song(2, "m4a"))
        val result = filterLibraryAudio(songs, LibraryAudioFilter(formats=setOf("ALAC"))) {
            AudioInfo(if (it.id==1L) "ALAC" else "AAC")
        }.toList().last()
        assertEquals(listOf(1L), result.songs.map { it.id })
    }
    @Test fun cancellingAfterFirstCheckpointStopsReadingObsoleteSelection() = runBlocking {
        val songs = (1L..20L).map { song(it, "flac") }
        var reads = 0
        filterLibraryAudio(songs, LibraryAudioFilter(qualities=setOf("HR"))) {
            // Simulate a disk probe; an instantaneous cache loop can finish before cancellation is dispatched.
            Thread.sleep(5)
            reads++; AudioInfo("FLAC", sampleRate=48000, bitDepth=24)
        }.take(2).toList()
        assertTrue(reads < songs.size)
    }
}
