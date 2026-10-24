package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerMode
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.posix.memcpy

@Composable
internal actual fun rememberSubtitleFontImporter(
    onPicked: (fileName: String?, bytes: ByteArray) -> Unit,
    onError: () -> Unit,
): () -> Unit {
    val currentOnPicked by rememberUpdatedState(onPicked)
    val currentOnError by rememberUpdatedState(onError)
    val delegateHolder = remember { mutableStateOf<SubtitleFontPickerDelegate?>(null) }

    return remember<() -> Unit> {
        launch@{
            val presenter = topViewController()
            if (presenter == null) {
                currentOnError()
                return@launch
            }
            // Import mode copies the file into the app's inbox, which also works when the
            // app is sideloaded and security-scoped bookmarks are unavailable.
            val picker = UIDocumentPickerViewController(
                documentTypes = listOf(
                    "public.truetype-ttf-font",
                    "public.opentype-font",
                    "public.truetype-collection-font",
                    "public.font",
                    "public.data",
                ),
                inMode = UIDocumentPickerMode.UIDocumentPickerModeImport,
            )
            val delegate = SubtitleFontPickerDelegate(
                onPicked = { url ->
                    val didStartAccess = url.startAccessingSecurityScopedResource()
                    try {
                        val bytes = url.readBytes()
                        if (bytes == null) currentOnError() else currentOnPicked(url.lastPathComponent, bytes)
                    } catch (_: Throwable) {
                        currentOnError()
                    } finally {
                        if (didStartAccess) url.stopAccessingSecurityScopedResource()
                        delegateHolder.value = null
                    }
                },
                onDismissed = { delegateHolder.value = null },
            )
            delegateHolder.value = delegate
            picker.delegate = delegate
            presenter.presentViewController(picker, animated = true, completion = null)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSURL.readBytes(): ByteArray? {
    val data = NSData.dataWithContentsOfURL(this) ?: return null
    val size = data.length.toInt()
    if (size == 0) return null
    return ByteArray(size).apply {
        usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
    }
}

private class SubtitleFontPickerDelegate(
    private val onPicked: (NSURL) -> Unit,
    private val onDismissed: () -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
        if (url != null) onPicked(url) else onDismissed()
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onDismissed()
    }
}

private fun topViewController(): UIViewController? {
    var controller = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (controller?.presentedViewController != null) {
        controller = controller.presentedViewController
    }
    return controller
}
