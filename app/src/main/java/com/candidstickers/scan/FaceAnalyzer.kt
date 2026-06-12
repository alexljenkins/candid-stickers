package com.candidstickers.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker

/**
 * Two-stage MediaPipe pipeline: BlazeFace as a cheap "any faces at all?" screen,
 * then Face Landmarker (blendshapes on) only on photos that pass.
 */
class FaceAnalyzer(context: Context) : AutoCloseable {

    data class Face(val box: RectF, val blendshapes: Map<String, Float>)

    private val detector: FaceDetector = FaceDetector.createFromOptions(
        context,
        FaceDetector.FaceDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("blaze_face_short_range.tflite").build())
            .setRunningMode(RunningMode.IMAGE)
            .setMinDetectionConfidence(0.5f)
            .build()
    )

    private val landmarker: FaceLandmarker = FaceLandmarker.createFromOptions(
        context,
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(MAX_FACES)
            .setOutputFaceBlendshapes(true)
            .build()
    )

    fun hasFaces(bitmap: Bitmap): Boolean {
        val result = detector.detect(BitmapImageBuilder(bitmap).build())
        return result.detections().isNotEmpty()
    }

    fun analyze(bitmap: Bitmap): List<Face> {
        val result = landmarker.detect(BitmapImageBuilder(bitmap).build())
        val landmarks = result.faceLandmarks()
        val blendshapes = result.faceBlendshapes().orElse(emptyList())
        if (landmarks.isEmpty() || blendshapes.size != landmarks.size) return emptyList()

        return landmarks.mapIndexed { i, points ->
            var minX = 1f; var minY = 1f; var maxX = 0f; var maxY = 0f
            for (p in points) {
                if (p.x() < minX) minX = p.x()
                if (p.x() > maxX) maxX = p.x()
                if (p.y() < minY) minY = p.y()
                if (p.y() > maxY) maxY = p.y()
            }
            val box = RectF(
                minX * bitmap.width,
                minY * bitmap.height,
                maxX * bitmap.width,
                maxY * bitmap.height,
            )
            val shapes = blendshapes[i].associate { it.categoryName() to it.score() }
            Face(box, shapes)
        }
    }

    override fun close() {
        detector.close()
        landmarker.close()
    }

    companion object {
        const val MAX_FACES = 6
    }
}
