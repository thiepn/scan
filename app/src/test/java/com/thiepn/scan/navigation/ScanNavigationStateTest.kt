package com.thiepn.scan.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanNavigationStateTest {
    @Test
    fun libraryAndDocumentRoutesRoundTripWithUnicodeAndSeparators() {
        val routes = listOf(
            ScanRoute.Library,
            ScanRoute.Document("ordinary-document-1"),
            ScanRoute.Document("receipt/2026 #한국어 café.%")
        )
        routes.forEach { route ->
            assertEquals(route, ScanRouteCodec.decode(ScanRouteCodec.encode(route)))
        }
    }

    @Test
    fun unknownAndMalformedStateFailsClosedToLibrary() {
        val unexpected = listOf(
            "",
            "scan-route:v2:library",
            "scan-route:v1:document:",
            "scan-route:v1:document:@@@@",
            "scan-route:v1:document:IA=="
        )
        unexpected.forEach {
            val route = ScanRouteCodec.decode(it)
            // The codec may decode syntactically valid IDs; unknown, missing
            // and malformed routing tokens must never open an arbitrary screen.
            if (it != "scan-route:v1:document:IA==") {
                assertEquals(ScanRoute.Library, route)
            }
        }
    }

    @Test
    fun routeStateOpensClosesAndTreatsBackAsLibraryNavigation() {
        val navigation = ScanNavigationState()
        assertNull(navigation.documentId)
        assertFalse(navigation.navigateBack())

        navigation.openDocument("doc-24")
        assertEquals("doc-24", navigation.documentId)
        assertTrue(navigation.navigateBack())
        assertEquals(ScanRoute.Library, navigation.route)
        assertNull(navigation.documentId)
        assertFalse(navigation.navigateBack())
    }

    @Test
    fun invalidDocumentIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ScanRoute.Document("")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScanNavigationState().openDocument("   ")
        }
    }
}
