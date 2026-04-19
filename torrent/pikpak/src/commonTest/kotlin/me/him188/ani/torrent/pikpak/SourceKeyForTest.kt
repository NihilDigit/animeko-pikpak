package me.him188.ani.torrent.pikpak

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [sourceKeyFor] — the infohash/URL → stable bucket-name
 * mapping used by the engine's server-side slot folder. These have to be
 * pure (no network, no PikPak dependencies) because the result ends up as
 * a filename under the user's drive; a bug here bleeds across sources.
 */
class SourceKeyForTest {

    @Test
    fun `magnet infohash is extracted and uppercased`() {
        val uri = "magnet:?xt=urn:btih:abcdef1234567890abcdef1234567890abcdef12" +
                "&dn=example&tr=http://tracker.example.org"
        assertEquals("ABCDEF1234567890ABCDEF1234567890ABCDEF12", sourceKeyFor(uri))
    }

    @Test
    fun `magnet infohash case is normalised`() {
        val lower = "magnet:?xt=urn:btih:deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"
        val upper = "magnet:?xt=urn:btih:DEADBEEFDEADBEEFDEADBEEFDEADBEEFDEADBEEF"
        // Same infohash, different casing → same key.
        assertEquals(sourceKeyFor(lower), sourceKeyFor(upper))
    }

    @Test
    fun `magnet xt parameter position does not matter`() {
        val a = "magnet:?xt=urn:btih:0000111122223333444455556666777788889999&dn=x"
        val b = "magnet:?dn=x&xt=urn:btih:0000111122223333444455556666777788889999"
        assertEquals(sourceKeyFor(a), sourceKeyFor(b))
    }

    @Test
    fun `different magnets produce different keys`() {
        val a = "magnet:?xt=urn:btih:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val b = "magnet:?xt=urn:btih:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        assertNotEquals(sourceKeyFor(a), sourceKeyFor(b))
    }

    @Test
    fun `http torrent URL falls back to h-prefixed hash`() {
        val url = "https://example.org/path/to/file.torrent?k=v"
        val key = sourceKeyFor(url)
        assertTrue(key.startsWith("h-"), "fallback should be prefixed with 'h-' to avoid infohash clash: $key")
        // The bucket-name must be a plain ASCII token PikPak won't reject.
        assertTrue(key.matches(Regex("h-[0-9a-f]+")), "unexpected fallback shape: $key")
    }

    @Test
    fun `fallback is stable across invocations`() {
        val url = "https://example.org/some.torrent"
        assertEquals(sourceKeyFor(url), sourceKeyFor(url))
    }

    @Test
    fun `magnet without infohash falls back to URL hash`() {
        val weird = "magnet:?dn=missing-xt"
        val key = sourceKeyFor(weird)
        assertTrue(key.startsWith("h-"))
    }
}
