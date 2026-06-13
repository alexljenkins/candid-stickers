package com.candidstickers.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import kotlin.math.hypot

/**
 * Two-stage MediaPipe pipeline: BlazeFace as a cheap "any faces at all?" screen,
 * then Face Landmarker (blendshapes on) only on photos that pass.
 */
class FaceAnalyzer(context: Context) : AutoCloseable {

    /**
     * ArcFace 5-point alignment sources, in pixel coordinates of the analyzed
     * bitmap. Eye and mouth pairs are ordered by ascending image x (so
     * [leftEye]/[mouthLeft] are image-left, matching the ArcFace template).
     * Faces with [interOcularPx] < [MIN_INTER_OCULAR_PX] in source pixels are
     * too small for a reliable face embedding and should be skipped.
     */
    data class Keypoints(
        val leftEye: PointF,
        val rightEye: PointF,
        val nose: PointF,
        val mouthLeft: PointF,
        val mouthRight: PointF,
        val interOcularPx: Float,
    )

    data class Face(
        val box: RectF,
        val blendshapes: Map<String, Float>,
        val keypoints: Keypoints,
    )

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
            Face(box, shapes, keypoints(points, bitmap.width, bitmap.height))
        }
    }

    /**
     * Builds the 5-point alignment sources from the 478 FaceLandmarker
     * landmarks: leftEye=iris center 468, rightEye=473 (eye-corner midpoints
     * 33/133 and 362/263 when irises are absent), nose=1, mouth corners 61/291.
     */
    private fun keypoints(points: List<NormalizedLandmark>, width: Int, height: Int): Keypoints {
        fun px(index: Int) = PointF(points[index].x() * width, points[index].y() * height)
        fun mid(a: Int, b: Int): PointF {
            val pa = px(a); val pb = px(b)
            return PointF((pa.x + pb.x) / 2f, (pa.y + pb.y) / 2f)
        }

        val hasIris = points.size >= IRIS_LANDMARK_COUNT
        var eyeA = if (hasIris) px(468) else mid(33, 133)
        var eyeB = if (hasIris) px(473) else mid(362, 263)
        if (eyeA.x > eyeB.x) { val t = eyeA; eyeA = eyeB; eyeB = t }

        var mouthA = px(61)
        var mouthB = px(291)
        if (mouthA.x > mouthB.x) { val t = mouthA; mouthA = mouthB; mouthB = t }

        return Keypoints(
            leftEye = eyeA,
            rightEye = eyeB,
            nose = px(1),
            mouthLeft = mouthA,
            mouthRight = mouthB,
            interOcularPx = hypot(eyeB.x - eyeA.x, eyeB.y - eyeA.y),
        )
    }

    override fun close() {
        detector.close()
        landmarker.close()
    }

    companion object {
        const val MAX_FACES = 6

        /** FaceLandmarker emits 478 points when iris refinement is on (last 10 are irises). */
        private const val IRIS_LANDMARK_COUNT = 478

        /** Below this inter-ocular distance (source px) face embeddings are unreliable. */
        const val MIN_INTER_OCULAR_PX = 40f
    }
}
