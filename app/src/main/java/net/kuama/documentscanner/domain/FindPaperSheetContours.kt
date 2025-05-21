package net.kuama.documentscanner.domain

import android.graphics.Bitmap
import net.kuama.documentscanner.data.Corners
import net.kuama.documentscanner.data.CornersFactory
import net.kuama.documentscanner.extensions.shape
import net.kuama.documentscanner.support.*
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.core.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.hypot
import kotlin.math.abs

class FindPaperSheetContours : InfallibleUseCase<Corners?, FindPaperSheetContours.Params>() {

    class Params(val bitmap: Bitmap)

    override suspend fun run(params: Params): Corners? {
        val original = Mat()
        Utils.bitmapToMat(params.bitmap, original)
        // Try multiple detection strategies
        val results = listOfNotNull(
            tryStandardDetection(original),
            tryAdaptiveThresholdDetection(original),
        )
        // Return the best result based on area and aspect ratio
        return getBestResult(results, original.size())
    }

    private fun tryStandardDetection(original: Mat): Corners? {
        val modified = Mat()
        // Convert image from RGBA to GrayScale
        Imgproc.cvtColor(original, modified, Imgproc.COLOR_RGBA2GRAY)

        // Strong Gaussian Filter
        Imgproc.GaussianBlur(modified, modified, Size(51.0, 51.0), 0.0)

        // Canny Edge Detection
        Imgproc.Canny(modified, modified, 100.0, 200.0, 5, false)

        // Closing: Dilation followed by Erosion
        Imgproc.dilate(
            modified, modified, Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT, Size(8.0, 8.0)
            )
        )
        Imgproc.erode(
            modified, modified, Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT, Size(3.0, 3.0)
            )
        )

        var contours: MutableList<MatOfPoint> = ArrayList()
        val hierarchy = Mat()
        Imgproc.findContours(
            modified, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE
        )

        hierarchy.release()
        contours = contours
            .filter { it.shape.size == 4 }
            .toTypedArray()
            .toMutableList()

        contours.sortWith { lhs, rhs ->
            Imgproc.contourArea(rhs).compareTo(Imgproc.contourArea(lhs))
        }

        val result: Corners? = contours.firstOrNull()?.let {
            CornersFactory.create(it.shape, original.size())
        }

        return result
    }

    private fun tryAdaptiveThresholdDetection(original: Mat): Corners? {
        val modified = Mat()
        Imgproc.cvtColor(original, modified, Imgproc.COLOR_RGBA2GRAY)
        // Calculate adaptive blockSize based on image dimensions
        val blockSize = (min(modified.width(), modified.height()) * 0.05).toInt()
        val adaptiveBlockSize = if (blockSize % 2 == 0) blockSize + 1 else blockSize
        // Apply adaptive threshold
        Imgproc.adaptiveThreshold(
            modified,
            modified,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV,
            adaptiveBlockSize,
            7.0
        )
        // Apply morphological operations to clean up the threshold result
        val dilateSize = max(4.0, modified.width() * 0.005)
        Imgproc.dilate(
            modified, modified, Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT, Size(dilateSize, dilateSize)
            )
        )
        return findContours(modified, original.size())
    }

    private fun findContours(processedImage: Mat, originalSize: Size): Corners? {
        val contours: MutableList<MatOfPoint> = ArrayList()
        val hierarchy = Mat()
        Imgproc.findContours(
            processedImage,
            contours,
            hierarchy,
            Imgproc.RETR_LIST,
            Imgproc.CHAIN_APPROX_SIMPLE
        )
        hierarchy.release()
        // Filter contours based on enhanced criteria
        val filteredContours = contours.filter { contour ->
            // Approximate the contour to reduce number of points
            val peri = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
            val approxCurve = MatOfPoint2f()
            Imgproc.approxPolyDP(MatOfPoint2f(*contour.toArray()), approxCurve, 0.02 * peri, true)
            // Convert back to MatOfPoint for contour operations
            val approxPoints = approxCurve.toArray()
            // Check if it's a quadrilateral (4 corners)
            if (approxPoints.size != 4) return@filter false
            // Ensure significant area (at least 10% of the image)
            val area = Imgproc.contourArea(contour)
            val imgArea = originalSize.width * originalSize.height
            if (area < imgArea * 0.1) return@filter false
            // Check for convexity
            val isConvex = Imgproc.isContourConvex(MatOfPoint(*approxPoints))
            if (!isConvex) return@filter false
            // Check reasonable aspect ratio (between 0.5 and 2.0)
            val rect = Imgproc.boundingRect(MatOfPoint(*approxPoints))
            val aspectRatio = rect.width.toDouble() / rect.height.toDouble()
            if (aspectRatio < 0.5 || aspectRatio > 2.0) return@filter false
            true
        }
        // Sort contours by area (largest first)
        val sortedContours = filteredContours.sortedByDescending {
            Imgproc.contourArea(it)
        }
        return sortedContours.firstOrNull()?.let {
            // Use approximated contour for more accurate corners
            val peri = Imgproc.arcLength(MatOfPoint2f(*it.toArray()), true)
            val approxCurve = MatOfPoint2f()
            Imgproc.approxPolyDP(MatOfPoint2f(*it.toArray()), approxCurve, 0.02 * peri, true)
            val approxContour = MatOfPoint(*approxCurve.toArray())
            CornersFactory.create(approxContour.shape, originalSize)
        }
    }

    private fun getBestResult(results: List<Corners>, originalSize: Size): Corners? {
        if (results.isEmpty()) return null
        if (results.size == 1) return results.first()
        // Score results based on:
        // 1. Area coverage (larger is better)
        // 2. Aspect ratio (closer to standard paper ratio is better)
        // 3. Corner positions (more evenly distributed is better)
        val standardAspectRatio = 1.414 // A4 paper ratio
        return results.maxByOrNull { corners ->
            // Calculate area
            val area = calculateQuadrilateralArea(corners)
            val maxArea = originalSize.width * originalSize.height
            val areaScore = area / maxArea
            // Calculate aspect ratio score (1.0 is perfect)
            val width = max(
                hypot(corners.topRight.x - corners.topLeft.x, corners.topRight.y - corners.topLeft.y),
                hypot(corners.bottomRight.x - corners.bottomLeft.x, corners.bottomRight.y - corners.bottomLeft.y)
            )
            val height = max(
                hypot(corners.bottomLeft.x - corners.topLeft.x, corners.bottomLeft.y - corners.topLeft.y),
                hypot(corners.bottomRight.x - corners.topRight.x, corners.bottomRight.y - corners.topRight.y)
            )
            val aspectRatio = width / height
            val aspectRatioScore = 1.0 - min(abs(aspectRatio - standardAspectRatio), 1.0)
            // Final weighted score
            (areaScore * 0.6) + (aspectRatioScore * 0.4)
        }
    }

    private fun calculateQuadrilateralArea(corners: Corners): Double {
        // Use Shoelace formula to calculate area
        val x = listOf(corners.topLeft.x, corners.topRight.x, corners.bottomRight.x, corners.bottomLeft.x)
        val y = listOf(corners.topLeft.y, corners.topRight.y, corners.bottomRight.y, corners.bottomLeft.y)
        var area = 0.0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            area += x[i] * y[j]
            area -= y[i] * x[j]
        }
        return abs(area) / 2.0
    }
}
