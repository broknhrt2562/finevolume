package dev.finevolume.app.data

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.finevolume.app.domain.model.AppMetadata
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppMetadataCache @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cache = ConcurrentHashMap<String, AppMetadata>()
    private val packageManager: PackageManager = context.packageManager

    fun getAppMetadata(packageName: String): AppMetadata {
        cache[packageName]?.let { return it }

        val metadata = try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val label = packageManager.getApplicationLabel(appInfo).toString()
            val drawable = packageManager.getApplicationIcon(appInfo)
            val iconBitmap = drawableToImageBitmap(drawable)
            AppMetadata(packageName = packageName, appName = label, icon = iconBitmap)
        } catch (_: Exception) {
            val cleanName = packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
            AppMetadata(packageName = packageName, appName = cleanName, icon = null)
        }

        cache[packageName] = metadata
        return metadata
    }

    private fun drawableToImageBitmap(drawable: Drawable): ImageBitmap? {
        return try {
            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                drawable.bitmap.asImageBitmap()
            } else {
                val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
                val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bitmap.asImageBitmap()
            }
        } catch (_: Throwable) {
            null
        }
    }
}
