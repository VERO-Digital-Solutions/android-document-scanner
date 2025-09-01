package net.kuama.documentscanner.presentation

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import net.kuama.documentscanner.data.Corners
import org.opencv.core.Point
import kotlin.math.abs

/**
 * Custom view that draws a rectangle and allows the user to move the corner points.
 * The rectangle is defined by four corner points and can be resized and repositioned.
 */
class PaperRectangle : View {
    constructor(context: Context) : super(context)

    constructor(context: Context, attributes: AttributeSet) : super(context, attributes)

    constructor(context: Context, attributes: AttributeSet, defTheme: Int) : super(context, attributes, defTheme)

    // Paint objects for drawing the shapes and lines
    private val rectPaint = Paint()        // Paint for drawing the rectangle lines
    private val extCirclePaint = Paint()   // Paint for external circles
    private val intCirclePaint = Paint()   // Paint for internal circles
    private val intCirclePaintR = Paint()  // Paint for internal circles (red)
    private val extCirclePaintR = Paint()  // Paint for external circles (red)
    private val fillPaint = Paint()       // Paint for filling the rectangle shape

    // Ratios for resizing the points to fit the view's dimensions
    private var ratioX: Double = 1.0
    private var ratioY: Double = 1.0

    // Current corner points of the rectangle
    private var topLeft: Point = Point()
    private var topRight: Point = Point()
    private var bottomRight: Point = Point()
    private var bottomLeft: Point = Point()

    // Path object to define the rectangle shape
    private val path: Path = Path()

    // Variables for tracking touch events and moving points
    private var point2Move = Point()
    private var cropMode = false
    private var latestDownX = 0.0F
    private var latestDownY = 0.0F

    init {
        // Initialize paint objects with desired styles and colors

        // Paint for drawing the rectangle lines
        rectPaint.color = Color.parseColor("#3454D1")
        rectPaint.isAntiAlias = true
        rectPaint.isDither = true
        rectPaint.strokeWidth = 6F
        rectPaint.style = Paint.Style.STROKE
        rectPaint.strokeJoin = Paint.Join.ROUND // set the join to round you want
        rectPaint.strokeCap = Paint.Cap.ROUND // set the paint cap to round too
        rectPaint.pathEffect = CornerPathEffect(10f)

        // Paint for filling the rectangle shape
        fillPaint.color = Color.parseColor("#3454D1")
        fillPaint.alpha = 60
        fillPaint.isAntiAlias = true
        fillPaint.isDither = true
        fillPaint.strokeWidth = 6F
        fillPaint.style = Paint.Style.FILL
        fillPaint.strokeJoin = Paint.Join.ROUND // set the join to round you want
        fillPaint.strokeCap = Paint.Cap.ROUND // set the paint cap to round too
        fillPaint.pathEffect = CornerPathEffect(10f)

        // Paints for drawing external and internal circles
        extCirclePaint.color = Color.parseColor("#3454D1")
        extCirclePaint.isDither = true
        extCirclePaint.isAntiAlias = true
        extCirclePaint.strokeWidth = 8F
        extCirclePaint.style = Paint.Style.STROKE

        intCirclePaint.color = Color.DKGRAY
        intCirclePaint.isDither = true
        intCirclePaint.isAntiAlias = true
        intCirclePaint.strokeWidth = 10F
        intCirclePaint.style = Paint.Style.FILL

        intCirclePaintR.color = Color.RED
        intCirclePaintR.isDither = true
        intCirclePaintR.isAntiAlias = true
        intCirclePaintR.strokeWidth = 10F
        intCirclePaintR.style = Paint.Style.FILL

        extCirclePaintR.color = Color.RED
        extCirclePaintR.isDither = true
        extCirclePaintR.isAntiAlias = true
        extCirclePaintR.strokeWidth = 8F
        extCirclePaintR.style = Paint.Style.STROKE
    }

    /**
     * Method to update the corner points and set the cropMode to true
     * @param corners The detected corners of the rectangle
     * @param width The width of the original image
     * @param height The height of the original image
     */
    fun onCorners(corners: Corners, width: Int, height: Int) {
        cropMode = true
        ratioX = corners.size.width.div(width)
        ratioY = corners.size.height.div(height)
        topLeft = corners.topLeft
        topRight = corners.topRight
        bottomRight = corners.bottomRight
        bottomLeft = corners.bottomLeft

        resize()
        path.reset()
        path.close()
        invalidate()
    }

    /**
     * Method to update the corner points when corners are detected but not finalized
     * @param corners The detected corners of the rectangle
     */
    fun onCornersDetected(corners: Corners) {
        ratioX = corners.size.width.div(measuredWidth)
        ratioY = corners.size.height.div(measuredHeight)
        topLeft = corners.topLeft
        topRight = corners.topRight
        bottomRight = corners.bottomRight
        bottomLeft = corners.bottomLeft

        resize()
        path.reset()

        updateRect()

        path.close()
        invalidate()
    }

    /**
     * Method to reset the view when corners are not detected
     */
    fun onCornersNotDetected() {
        path.reset()
        invalidate()
    }

    // Override onDraw method to draw the rectangle and circles
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, rectPaint)

        if (cropMode) {
            // Draw circles at the corner points

            canvas.drawCircle(topLeft.x.toFloat(), topLeft.y.toFloat(), 40F, extCirclePaint)
            canvas.drawCircle(topRight.x.toFloat(), topRight.y.toFloat(), 40F, extCirclePaint)
            canvas.drawCircle(bottomLeft.x.toFloat(), bottomLeft.y.toFloat(), 40F, extCirclePaint)
            canvas.drawCircle(bottomRight.x.toFloat(), bottomRight.y.toFloat(), 40F, extCirclePaint)

            canvas.drawCircle(topLeft.x.toFloat(), topLeft.y.toFloat(), 35F, intCirclePaint)
            canvas.drawCircle(topRight.x.toFloat(), topRight.y.toFloat(), 35F, intCirclePaint)
            canvas.drawCircle(bottomLeft.x.toFloat(), bottomLeft.y.toFloat(), 35F, intCirclePaint)
            canvas.drawCircle(bottomRight.x.toFloat(), bottomRight.y.toFloat(), 35F, intCirclePaint)
        }
    }

    /**
     * Method to handle touch events for moving corner points
     */
    fun onTouch(event: MotionEvent?): Boolean {
        if (!cropMode) {
            return false
        }

        when (event?.action) {
            MotionEvent.ACTION_DOWN -> {
                latestDownX = event.x
                latestDownY = event.y
                calculatePoint2Move(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                point2Move.x = (event.x - latestDownX) + point2Move.x
                point2Move.y = (event.y - latestDownY) + point2Move.y
                movePoints()
                latestDownY = event.y
                latestDownX = event.x
                invalidate()
            }
        }
        return true
    }

    /** Update the path with the new corner points */
     fun updateRect() {
        path.moveTo(topLeft.x.toFloat(), topLeft.y.toFloat())
        path.lineTo(topRight.x.toFloat(), topRight.y.toFloat())
        path.lineTo(bottomRight.x.toFloat(), bottomRight.y.toFloat())
        path.lineTo(bottomLeft.x.toFloat(), bottomLeft.y.toFloat())

        path.close()
        invalidate()
    }

    /**
     * Method to move points when touch events occur
     */
    private fun movePoints() {
        path.reset()
        path.moveTo(topLeft.x.toFloat(), topLeft.y.toFloat())
        path.lineTo(topRight.x.toFloat(), topRight.y.toFloat())
        path.lineTo(bottomRight.x.toFloat(), bottomRight.y.toFloat())
        path.lineTo(bottomLeft.x.toFloat(), bottomLeft.y.toFloat())
        path.close()
    }

    /**
     * Method to calculate which point to move during touch events
     */
    private fun calculatePoint2Move(downX: Float, downY: Float) {
        val points = listOf(topLeft, topRight, bottomRight, bottomLeft)
        point2Move = points.minByOrNull { abs((it.x - downX).times(it.y - downY)) } ?: topLeft
    }

    /**
     * Method to resize the corner points based on the view's dimensions
     */
    private fun resize() {
        topLeft.x = topLeft.x.div(ratioX)
        topLeft.y = topLeft.y.div(ratioY)
        topRight.x = topRight.x.div(ratioX)
        topRight.y = topRight.y.div(ratioY)
        bottomRight.x = bottomRight.x.div(ratioX)
        bottomRight.y = bottomRight.y.div(ratioY)
        bottomLeft.x = bottomLeft.x.div(ratioX)
        bottomLeft.y = bottomLeft.y.div(ratioY)
    }
}
