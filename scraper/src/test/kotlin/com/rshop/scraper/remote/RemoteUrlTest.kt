package com.rshop.scraper.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteUrlTest {

    @Test
    fun `addresses round trip with spaces and accents`() {
        val url = RemoteUrl(RemoteProtocol.Sftp, "nas.local", 2222, "/Roms/Game Boy/Pokémon Rouge (F).zip")
        assertEquals("sftp://nas.local:2222/Roms/Game%20Boy/Pok%C3%A9mon%20Rouge%20%28F%29.zip", url.toString())
        assertEquals(url, RemoteUrl.parse(url.toString()))
        assertEquals("Pokémon Rouge (F).zip", url.fileName)
    }

    @Test
    fun `the default port is left out and filled in`() {
        val url = RemoteUrl.parse("FTP://Example.COM/games/a.zip")!!
        assertEquals(RemoteUrl(RemoteProtocol.Ftp, "example.com", 21, "/games/a.zip"), url)
        assertEquals("ftp://example.com/games/a.zip", url.toString())
        assertEquals(22, RemoteUrl.parse("sftp://h/x")!!.port)
        assertEquals(RemoteProtocol.Ftps, RemoteUrl.parse("ftps://h/x")!!.protocol)
    }

    @Test
    fun `credentials, other schemes and climbing paths are refused`() {
        assertNull(RemoteUrl.parse("ftp://user:pw@host/a"))
        assertNull(RemoteUrl.parse("ftp://user@host/a"))
        assertNull(RemoteUrl.parse("https://host/a"))
        assertNull(RemoteUrl.parse("ftp:///a"))
        assertNull(RemoteUrl.parse("sftp://host/a/../../etc/passwd"))
        assertNull(RemoteUrl.parse("not a url"))
    }

    @Test
    fun `paths are normalised`() {
        assertEquals("/a/b", RemoteUrl.normalizePath("//a/./b/"))
        assertEquals("/", RemoteUrl.normalizePath(""))
        assertNull(RemoteUrl.normalizePath("/a/../b"))
    }
}
