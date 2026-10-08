package com.thiepn.scan.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import java.util.Base64

/**
 * P23's typed destination contract. Scanner handoffs remain ActivityResult-
 * based; only library/document screens are app-owned routes at this stage.
 */
internal sealed interface ScanRoute {
    data object Library : ScanRoute
    data class Document(val id: String) : ScanRoute {
        init {
            require(id.isNotBlank()) { "A document route needs a nonblank ID." }
        }
    }
}

/**
 * Versioned, explicit saveable serialization. Never decode arbitrary route
 * strings as file paths or URIs. Unknown / malformed / future routes safely
 * restore the library instead.
 */
internal object ScanRouteCodec {
    private const val LIBRARY = "scan-route:v1:library"
    private const val DOCUMENT = "scan-route:v1:document:"

    fun encode(route: ScanRoute): String = when (route) {
        ScanRoute.Library -> LIBRARY
        is ScanRoute.Document -> DOCUMENT +
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(route.id.toByteArray(Charsets.UTF_8))
    }

    fun decode(raw: String): ScanRoute = when {
        raw == LIBRARY -> ScanRoute.Library
        raw.startsWith(DOCUMENT) ->
            runCatching {
                val encoded = raw.removePrefix(DOCUMENT)
                require(encoded.isNotBlank())
                val id = Base64.getUrlDecoder().decode(encoded)
                    .toString(Charsets.UTF_8)
                ScanRoute.Document(id)
            }.getOrDefault(ScanRoute.Library)
        else -> ScanRoute.Library
    }
}

@Stable
internal class ScanNavigationState(initialRoute: ScanRoute = ScanRoute.Library) {
    var route: ScanRoute by mutableStateOf(initialRoute)
        private set

    val documentId: String?
        get() = (route as? ScanRoute.Document)?.id

    fun openDocument(id: String) {
        route = ScanRoute.Document(id)
    }

    fun showLibrary() {
        route = ScanRoute.Library
    }

    fun navigateBack(): Boolean {
        if (route == ScanRoute.Library) return false
        showLibrary()
        return true
    }

    companion object {
        val Saver: Saver<ScanNavigationState, String> = Saver(
            save = { ScanRouteCodec.encode(it.route) },
            restore = { ScanNavigationState(ScanRouteCodec.decode(it)) }
        )
    }
}

@Composable
internal fun rememberScanNavigationState(): ScanNavigationState =
    rememberSaveable(saver = ScanNavigationState.Saver) {
        ScanNavigationState()
    }
