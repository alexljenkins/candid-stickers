package com.candidstickers.scan

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.tasks.await

/**
 * ML Kit Subject Segmentation wrapper. Returns a transparent-background bitmap
 * for the subject containing the given face, or null when the module is
 * unavailable or no subject overlaps the face (caller falls back to a flat crop).
 */
class SubjectMatte {

    private val segmenter = SubjectSegmentation.getClient(
        SubjectSegmenterOptions.Builder()
            .enableMultipleSubjects(
                SubjectSegmenterOptions.SubjectResultOptions.Builder()
                    .enableSubjectBitmap()
                    .build()
            )
            .build()
    )

    /**
     * @return the matted subject bitmap and its top-left offset in [bitmap] coords.
     */
    suspend fun matte(bitmap: Bitmap, faceBox: RectF): Matted? {
        val result = try {
            segmenter.process(InputImage.fromBitmap(bitmap, 0)).await()
        } catch (e: Exception) {
            // Module not yet downloaded / device unsupported — fall back silently.
            return null
        }
        val cx = faceBox.centerX()
        val cy = faceBox.centerY()
        val subject = result.subjects.firstOrNull { s ->
            cx >= s.startX && cx <= s.startX + s.width &&
                cy >= s.startY && cy <= s.startY + s.height
        } ?: return null
        val matted = subject.bitmap ?: return null
        return Matted(matted, subject.startX, subject.startY)
    }

    fun close() = segmenter.close()

    data class Matted(val bitmap: Bitmap, val offsetX: Int, val offsetY: Int)
}
