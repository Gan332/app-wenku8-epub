package com.example.hyperreader

import com.example.hyperreader.settings.OnlineSource
import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineSourceTest {

    @Test
    fun missingOrInvalidNameFallsBackToLnr() {
        assertEquals(OnlineSource.LNR, OnlineSource.fromName(null))
        assertEquals(OnlineSource.LNR, OnlineSource.fromName(""))
        assertEquals(OnlineSource.LNR, OnlineSource.fromName("UNKNOWN"))
    }

    @Test
    fun explicitBuiltinIsStillSelectable() {
        assertEquals(OnlineSource.BUILTIN, OnlineSource.fromName("BUILTIN"))
        assertEquals(OnlineSource.LNR, OnlineSource.fromName("LNR"))
    }
}
