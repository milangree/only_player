package one.only.player.core.common.extensions

import android.net.Uri

val Uri.isExternalStorageDocument: Boolean
    get() = "com.android.externalstorage.documents" == authority

val Uri.isDownloadsDocument: Boolean
    get() = "com.android.providers.downloads.documents" == authority

val Uri.isMediaDocument: Boolean
    get() = "com.android.providers.media.documents" == authority

val Uri.isGooglePhotosUri: Boolean
    get() = "com.google.android.apps.photos.content" == authority

val Uri.isLocalPhotoPickerUri: Boolean
    get() = toString().contains("com.android.providers.media.photopicker")

val Uri.isCloudPhotoPickerUri: Boolean
    get() = toString().contains("com.google.android.apps.photos.cloudpicker")

fun Uri.toPrivateLogSummary(): String {
    val extension = lastPathSegment?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase().orEmpty()
    val hash = toString().hashCode().toUInt().toString(radix = 16)
    return "scheme=${scheme.orEmpty()} extension=$extension hash=$hash"
}

fun String.toPrivateLogSummary(): String = Uri.parse(this).toPrivateLogSummary()
