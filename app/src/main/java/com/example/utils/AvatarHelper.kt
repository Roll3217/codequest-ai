package com.example.utils

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.ImageView
import com.example.R
import com.example.model.AvatarOption
import java.io.File
import java.io.FileOutputStream

object AvatarHelper {

    val PREDEFINED_AVATARS: List<AvatarOption> = listOf(
        AvatarOption("avatar_cyber", "Cyber Coder", R.drawable.img_avatar_cyber),
        AvatarOption("avatar_neon", "Neon Architect", R.drawable.img_avatar_neon),
        AvatarOption("avatar_quantum", "Quantum Bot", R.drawable.img_avatar_quantum),
        AvatarOption("avatar_hacker", "Neural Hacker", R.drawable.ic_avatar_hacker),
        AvatarOption("avatar_ninja", "Algo Ninja", R.drawable.ic_avatar_ninja),
        AvatarOption("avatar_astronaut", "Astro Coder", R.drawable.ic_avatar_astronaut),
        AvatarOption("avatar_dragon", "Cyber Dragon", R.drawable.ic_avatar_dragon),
        AvatarOption("avatar_mech", "Synth Mech", R.drawable.ic_avatar_mech)
    )

    private const val MAX_AVATAR_DIMENSION_PX = 512

    fun getDrawableForKey(key: String): Int {
        return PREDEFINED_AVATARS.firstOrNull { it.id == key }?.drawableResId
            ?: R.drawable.img_avatar_cyber
    }

    fun loadAvatarInto(imageView: ImageView, avatarKey: String, isCustomPhoto: Boolean) {
        if (isCustomPhoto && avatarKey.isNotBlank()) {
            try {
                val file = File(avatarKey)
                if (file.exists() && file.length() > 0L) {
                    val bitmap = decodeSampledBitmapFromFile(file.absolutePath, MAX_AVATAR_DIMENSION_PX)
                    if (bitmap != null) {
                        imageView.setImageBitmap(bitmap)
                        return
                    }
                }
            } catch (_: Throwable) {
                // Fallback to predefined avatar if file is unreadable or too large
            }
        }
        imageView.setImageResource(getDrawableForKey(avatarKey))
    }

    /**
     * Safely downsamples, EXIF-rotates, and saves a picked gallery photo URI into internal app storage
     * (max 512x512 JPEG) so high-resolution camera photos never trigger OOM or Canvas crashes.
     */
    fun copyGalleryUriToInternalStorage(context: Context, uri: Uri): String? {
        return try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null
            }

            val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_AVATAR_DIMENSION_PX)
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
            var decoded = resolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOpts)
            } ?: return null

            val rotationDegrees = runCatching {
                resolver.openInputStream(uri)?.use { exifStream ->
                    val exif = android.media.ExifInterface(exifStream)
                    when (exif.getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL
                    )) {
                        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f
            }.getOrDefault(0f)

            if (rotationDegrees != 0f) {
                val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees) }
                val rotated = android.graphics.Bitmap.createBitmap(
                    decoded, 0, 0, decoded.width, decoded.height, matrix, true
                )
                if (rotated !== decoded) {
                    decoded.recycle()
                    decoded = rotated
                }
            }

            val maxSide = maxOf(decoded.width, decoded.height)
            if (maxSide > MAX_AVATAR_DIMENSION_PX) {
                val scale = MAX_AVATAR_DIMENSION_PX.toFloat() / maxSide.toFloat()
                val targetW = (decoded.width * scale).toInt().coerceAtLeast(1)
                val targetH = (decoded.height * scale).toInt().coerceAtLeast(1)
                val scaled = android.graphics.Bitmap.createScaledBitmap(decoded, targetW, targetH, true)
                if (scaled !== decoded) {
                    decoded.recycle()
                    decoded = scaled
                }
            }

            val avatarsDir = File(context.filesDir, "user_avatars")
            if (!avatarsDir.exists()) {
                avatarsDir.mkdirs()
            }
            val destFile = File(avatarsDir, "profile_${System.currentTimeMillis()}.jpg")
            FileOutputStream(destFile).use { output ->
                decoded.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, output)
            }
            decoded.recycle()

            if (destFile.exists() && destFile.length() > 0L) {
                destFile.absolutePath
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeSampledBitmapFromFile(path: String, maxDim: Int): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDim)
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(path, opts)
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var inSampleSize = 1
        var curW = width
        var curH = height
        while (curW > maxDim * 2 || curH > maxDim * 2) {
            curW /= 2
            curH /= 2
            inSampleSize *= 2
        }
        return inSampleSize.coerceAtLeast(1)
    }
}
