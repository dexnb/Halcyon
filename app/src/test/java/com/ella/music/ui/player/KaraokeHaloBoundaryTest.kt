package com.ella.music.ui.player

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class KaraokeHaloBoundaryTest {
    private fun alpha(stops: Array<Pair<Float,Color>>, x: Float): Float {
        val right = stops.indexOfFirst { it.first >= x }.coerceAtLeast(0)
        if (right == 0) return stops[0].second.alpha
        val left = stops[right-1]
        val next = stops[right]
        val width = next.first-left.first
        if (width <= 0f) return next.second.alpha
        val t = (x-left.first)/width
        return left.second.alpha+(next.second.alpha-left.second.alpha)*t
    }
    @Test fun glowFeathersInsideSungAreaAndNeverRevealsFutureLetters() {
        val mask=karaokeHaloMaskStops(0.5f,false,0.25f)
        assertEquals(1f,alpha(mask,0.2f),0.001f)
        assertEquals(0.5f,alpha(mask,0.375f),0.001f)
        assertEquals(0f,alpha(mask,0.5f),0.001f)
        assertEquals(0f,alpha(mask,0.8f),0.001f)
    }
    @Test fun rtlFeatherMirrorsTheSameSungBoundary() {
        val mask=karaokeHaloMaskStops(0.5f,true,0.25f)
        assertEquals(0f,alpha(mask,0.2f),0.001f)
        assertEquals(0.5f,alpha(mask,0.625f),0.001f)
        assertEquals(1f,alpha(mask,0.8f),0.001f)
    }
    @Test fun completedWordKeepsItsWholeHalo() {
        assertTrue(karaokeHaloMaskStops(1f,false,0.85f).all { it.second.alpha==1f })
        assertTrue(karaokeHaloMaskStops(0f,false,0.85f).all { it.second.alpha==0f })
    }
}
