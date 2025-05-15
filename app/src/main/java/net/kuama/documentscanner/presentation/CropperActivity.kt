package net.kuama.documentscanner.presentation

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.doOnNextLayout
import net.kuama.documentscanner.R
import net.kuama.documentscanner.data.Corners
import net.kuama.documentscanner.databinding.DsActivityCropperBinding
import net.kuama.documentscanner.exceptions.MissingCornersException
import net.kuama.documentscanner.extensions.hide
import net.kuama.documentscanner.extensions.loadBitmapFromView
import net.kuama.documentscanner.extensions.logError
import net.kuama.documentscanner.extensions.outputDirectory
import net.kuama.documentscanner.extensions.show
import net.kuama.documentscanner.extensions.toByteArray
import net.kuama.documentscanner.utils.PointUtils.arePointsOverlapping
import net.kuama.documentscanner.viewmodels.CropperViewModel
import org.opencv.core.Point
import org.opencv.core.Size
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.*

@SuppressLint("ClickableViewAccessibility")
class CropperActivity : AppCompatActivity() {
    private lateinit var cropModel: CropperViewModel
    private lateinit var bitmapUri: Uri
    private var screenOrientationDeg: Int = 0
    private lateinit var binding: DsActivityCropperBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DsActivityCropperBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val extras = intent.extras
        if (extras != null) {
            bitmapUri = intent.extras?.getString("lastUri")?.toUri() ?: error("invalid uri")
            screenOrientationDeg = if (intent.extras?.getInt("screenOrientationDeg") != null)
                intent.extras!!.getInt("screenOrientationDeg") else 0
        }

        val cropModel: CropperViewModel by viewModels()

        // Picture taken from User
        cropModel.originalBitmap.observe(this) {
            binding.cropPreview.setImageBitmap(cropModel.originalBitmap.value)
            binding.cropWrap.show()

            // Wait for bitmap to be loaded on view, then draw corners
            binding.cropWrap.doOnNextLayout {
                val detected = cropModel.corners.value
                val width = binding.cropPreview.measuredWidth
                val height = binding.cropPreview.measuredHeight
                val insetX = width * 0.1
                val insetY = height * 0.1
                val fallbackCorners = Corners(
                    topLeft = Point(insetX, insetY),
                    topRight = Point(width - insetX, insetY),
                    bottomLeft = Point(insetX, height - insetY),
                    bottomRight = Point(width - insetX, height - insetY),
                    size = Size(width.toDouble(), height.toDouble())
                )
                val resultingCorners = if (detected == null) {
                    fallbackCorners
                } else {
                    val point1 = detected.topLeft
                    val point2 = detected.topRight
                    val point3 = detected.bottomLeft
                    val point4 = detected.bottomRight
                    if (arePointsOverlapping(point1, point2) ||
                        arePointsOverlapping(point1, point3) ||
                        arePointsOverlapping(point1, point4) ||
                        arePointsOverlapping(point2, point3) ||
                        arePointsOverlapping(point2, point4) ||
                        arePointsOverlapping(point3, point4)
                    ) {
                        fallbackCorners
                    } else {
                        detected
                    }
                }
                cropModel.corners.value = resultingCorners
                binding.cropHud.onCorners(
                    corners = resultingCorners,
                    height = height,
                    width = width
                )
                binding.cropHud.updateRect()
            }
        }

        cropModel.errors.observe(this) { error ->
            when (error) {
                is MissingCornersException -> {
                    finish()
                    Toast.makeText(this, "Corners not detected", Toast.LENGTH_SHORT)
                        .show()
                }
                else -> {
                    logError("croperror", "${error.message}")

                    // The original file is deleted but we don't need it
                    if (!error.message.toString().endsWith("(No such file or directory)")) {
                        Toast.makeText(
                            this,
                            this.resources.getText(R.string.ds_crop_error),
                            Toast.LENGTH_SHORT
                        )
                            .show()
                    }
                }
            }
        }

        cropModel.bitmapToCrop.observe(this) {
            binding.cropResultPreview.setImageBitmap(cropModel.bitmapToCrop.value)
        }

        setOnCloseCropPreviewClicked()
        setOnCloseResultPreviewClicked()
        setOnConfirmCropPreviewClicked()
        setOnConfirmCropResultClicked()
        setFullscreen()
        setOnCropPreviewTouched()

        this.cropModel = cropModel
    }

    override fun onResume() {
        super.onResume()
        cropModel.onViewCreated(bitmapUri, screenOrientationDeg)
    }

    private fun setFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            this.window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            this.window.setDecorFitsSystemWindows(false)
            this.window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else
            @Suppress("DEPRECATION")
            this.window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    )
    }

    private fun setOnCloseCropPreviewClicked() {
        binding.closeCropPreview.setOnClickListener {
            closeActivity()
        }
    }

    private fun setOnCloseResultPreviewClicked() {
        binding.closeResultPreview.setOnClickListener {
            closeActivity()
        }
    }

    private fun setOnConfirmCropPreviewClicked() {
        binding.confirmCropPreview.setOnClickListener {
            binding.cropWrap.hide()
            binding.cropHud.hide()
            val bitmapToCrop = loadBitmapFromView(binding.cropPreview)

            cropModel.onCornersAccepted(bitmapToCrop)

            binding.cropResultWrap.show()
        }
    }

    private fun setOnConfirmCropResultClicked() {
        binding.confirmCropResult.setOnClickListener {
            val file = File(this.outputDirectory(), "${UUID.randomUUID()}.jpg")

            val outputStream = FileOutputStream(file)
            val byteArrayOutputStream = ByteArrayOutputStream()
            outputStream.write(cropModel.bitmapToCrop.value?.toByteArray(byteArrayOutputStream))
            outputStream.close()
            byteArrayOutputStream.close()

            val resultIntent = Intent()
            resultIntent.putExtra("croppedPath", file.absolutePath)
            setResult(RESULT_OK, resultIntent)
            finish()
        }
    }

    private fun setOnCropPreviewTouched() {

        binding.cropPreview.setOnTouchListener { view: View, motionEvent: MotionEvent ->
            view.performClick()
            binding.cropHud.onTouch(motionEvent)
        }
    }

    private fun closeActivity() {
        this.setResult(Activity.RESULT_CANCELED)
        finish()
    }
}
