package com.skamz.shadercam.ui.activities

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.*
import android.media.Image
import android.net.Uri
import android.opengl.GLES20.*
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Log.*
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.room.Room
import androidx.room.RoomDatabase
import com.google.android.material.slider.Slider
import com.otaliastudios.cameraview.CameraListener
import com.otaliastudios.cameraview.CameraView
import com.otaliastudios.cameraview.PictureResult
import com.otaliastudios.cameraview.VideoResult
import com.otaliastudios.cameraview.controls.Mode
import com.otaliastudios.cameraview.frame.Frame
import com.otaliastudios.cameraview.frame.FrameProcessor
import com.otaliastudios.cameraview.size.Size
import com.otaliastudios.opengl.core.EglCore
import com.otaliastudios.opengl.program.GlShader
import com.otaliastudios.opengl.surface.EglWindowSurface
import com.otaliastudios.opengl.texture.GlTexture
import com.skamz.shadercam.*
import com.skamz.shadercam.databinding.ActivityCameraBinding
import com.skamz.shadercam.logic.database.AppDatabase
import com.skamz.shadercam.logic.database.ShaderDaoWrapper
import com.skamz.shadercam.logic.shaders.camera_view_defaults.NoopShader
import com.skamz.shadercam.logic.shaders.util.*
import com.skamz.shadercam.logic.util.IoUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.*
import java.nio.ByteBuffer
import java.util.*


//test
class CameraActivity : AppCompatActivity() {
    lateinit var camera: CameraView
    private var mode:Mode = Mode.PICTURE
    private var showParams: Boolean = true

    private lateinit var viewBinding: ActivityCameraBinding

    companion object {
        private const val TAG = "DEBUG"
        var shaderAttributes: ShaderAttributes = GenericShader.shaderAttributes
//            set(value) {
//                // Due to BaseFilter (and therefore GenericShader) internally using
//                // .newInstance() while capturing photo/video, we cannot pass arguments to the
//                // constructor. So, it is instead configured using the `shaderAttributes` static property.
//                GenericShader.shaderAttributes = value
//                shader = GenericShader()
//                field = value
//            }

        var shader: GenericShader = GenericShader()

        var shaderHasError: Boolean = false
        var shaderErrorMsg: String? = null

        lateinit var db: RoomDatabase
        lateinit var shaderDao: ShaderDaoWrapper

        // Returns null if shader is valid. Otherwise, returns error message
        fun validateShader(shader: GenericShader): String? {
            val core = EglCore()
            val texture = GlTexture()
            val surfaceTexture = SurfaceTexture(texture.id)
            val window = EglWindowSurface(core, surfaceTexture)
            window.makeCurrent()

            try {
                GlShader(GL_FRAGMENT_SHADER, shader.fragmentShader)
            } catch (e: java.lang.RuntimeException) {
                return e.message
            } finally {
                core.release()
            }
            return null
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java, "shadercam-db"
        ).fallbackToDestructiveMigration().build()

        shaderDao = ShaderDaoWrapper(db as AppDatabase)

        findViewById<Button>(R.id.editor_link).setOnClickListener {
            val intent = Intent(this, EditorActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        }

        findViewById<Button>(R.id.shader_editor_link).setOnClickListener {
            val intent = Intent(this, ShaderSelectActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        }

        findViewById<ImageButton>(R.id.log_in_out).setOnClickListener {
            val intent = Intent(this, OnboardingBaseActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            intent.putExtra("KEEP_VALUES", true)
            startActivity(intent)
        }

        findViewById<ImageButton>(R.id.camera_switch_front_back).setOnClickListener {
            camera.toggleFacing()
        }

        val captureBtn = findViewById<ImageButton>(R.id.camera_capture_btn)
        captureBtn.setOnClickListener {
            if (mode == Mode.PICTURE) {
                camera.takePictureSnapshot() // See MyCameraListener for callback
                Toast.makeText(applicationContext, "Took Picture", Toast.LENGTH_SHORT).show()
            } else {
                if (camera.isTakingVideo) {
                    captureBtn.setBackgroundColor(ContextCompat.getColor(this, R.color.white))
                    camera.stopVideo()
                } else {
                    captureBtn.setBackgroundColor(ContextCompat.getColor(this, R.color.red))
                    val path = IoUtil.buildVideoPath(this)
                    camera.takeVideoSnapshot(File(path))
                }
            }
        }

        val paramToggleButton = findViewById<ImageButton>(R.id.params_toggle)
        paramToggleButton.setOnClickListener {
            showParams = !showParams
            val uiContainer = findViewById<LinearLayout>(R.id.dynamic_ui)
            uiContainer.visibility = if (showParams) View.VISIBLE else View.GONE
            val shaderTitle = findViewById<TextView>(R.id.shader_title)
            shaderTitle.visibility = if (showParams) View.VISIBLE else View.GONE
        }

        val switchModeBtn = findViewById<ImageButton>(R.id.switch_photo_video)
        switchModeBtn.setOnClickListener {
            if (mode == Mode.PICTURE) {
                mode = Mode.VIDEO
                switchModeBtn.setImageResource(R.drawable.camera_mode_icon)
                Toast.makeText(applicationContext, "Switched to Video mode", Toast.LENGTH_SHORT).show()
            } else {
                mode = Mode.PICTURE
                switchModeBtn.setImageResource(R.drawable.video_mode_icon)
                Toast.makeText(applicationContext, "Switched to Picture mode", Toast.LENGTH_SHORT).show()
            }
            camera.mode = mode
        }

        camera = findViewById(R.id.camera_view)
        camera.setLifecycleOwner(this)
        camera.addCameraListener(MyCameraListener(this))
        setShader(shaderAttributes)
//        setupFrameProcessor()
    }

    fun setupFrameProcessor() {
        camera.addFrameProcessor(object : FrameProcessor {
            override fun process(frame: Frame) {
//                val time: Long = frame.getTime()
                val size: Size = frame.getSize()
//                val format: Int = frame.getFormat()
//                val userRotation: Int = frame.getRotationToUser()
//                val viewRotation: Int = frame.getRotationToView()
                if (frame.getDataClass() === ByteArray::class.java) {
                    val out = ByteArrayOutputStream()
                    val yuvImage = YuvImage(
                        frame.getData(),
                        ImageFormat.NV21,
                        frame.getSize().getWidth(),
                        frame.getSize().getHeight(),
                        null
                    )
                    yuvImage.compressToJpeg(
                        Rect(0, 0, frame.getSize().getWidth(), frame.getSize().getHeight()),
                        90,
                        out
                    )
                    val imageBytes = out.toByteArray()
                    val bmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
//                    Log.e("DEBUG", "is byte arr")
//                    val bmp = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
//                    Log.e("DEBUG", " wrapping")
//                    val buffer = ByteBuffer.wrap(frame.getData())
//                    Log.e("DEBUG", " copying")
//                    bmp.copyPixelsFromBuffer(buffer)
//                    val data: ByteArray = frame.getData()
//                    val bmp = BitmapFactory.decodeByteArray(data, 0, data.count())
//                    Log.e("DEBUG", " Setting prev Frame")
                    GenericShader.prevFrame = bmp
//                    Log.e("DEBUG", " Null after set? ${bmp == null}")

                } else if (frame.getDataClass() === Image::class.java) {
                    Log.e("Debug", "its an image")
                    val data: Image = frame.getData()
                    val buffer: ByteBuffer = data.planes[0].getBuffer();
                    val byteArray = ByteArray(buffer.capacity())
                    buffer.get(byteArray)
                    val bmp: Bitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.count(), null)
                    GenericShader.prevFrame = bmp

//                    (camera.filter as GenericShader).prevFrame = bmp
                } else {
                    Log.e("Debug", "something else entirely.")
                }
            }
        })
    }

    class MyCameraListener(parent: CameraActivity) : CameraListener() {
        private var cameraActivity: CameraActivity = parent
        override fun onPictureTaken(result: PictureResult) {
            result.toBitmap { bmp ->
                val path = IoUtil.buildPhotoPath(cameraActivity)
                i(TAG, path)
                IoUtil.saveImage(bmp!!, path, cameraActivity)
            }
        }

        override fun onVideoTaken(result: VideoResult) {
            super.onVideoTaken(result)
            VideoPreviewActivity.videoResult = result
            val intent = Intent(cameraActivity, VideoPreviewActivity::class.java)
            cameraActivity.startActivity(intent)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setShader(shaderAttributes)

        val deletedShaderName = intent?.getStringExtra("DeletedShader")
        if (deletedShaderName != null) {
            Toast.makeText(this, "Deleted shader $deletedShaderName", Toast.LENGTH_SHORT).show()
            shaderAttributes = NoopShader
            setShader(shaderAttributes)
        }

        val updatedColorName = intent?.getStringExtra("UPDATED_COLOR_NAME")
        if (updatedColorName != null) {
            val updatedColorValue = intent!!.getIntExtra("UPDATED_COLOR_VALUE", Color.BLACK)
            updateShaderParam(updatedColorName, updatedColorValue!!)
            setShader(shaderAttributes) // TODO: don't really need to rebuild the whole shader here.
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun updateShaderParam(paramName: String, value: Any) {
        shader.setValue(paramName, value)
        updateShaderText()
    }

    private fun Float.format(digits: Int) = "%.${digits}f".format(this)

    @RequiresApi(Build.VERSION_CODES.O)
    private fun updateShaderText(nameOverride: String? = null) {
        val shaderTitle = findViewById<TextView>(R.id.shader_title)
        var text = "Current shader: ${nameOverride ?: shader.name}"
        if (shader.params.isNotEmpty()) {
            var paramHints = ""
            shader.params.forEachIndexed { index, shaderParam ->
                val shaderVal = shader.dataValues[shaderParam.paramName]
                val shaderValString: String = when(shaderParam.paramType) {
                    "float" -> {
                        if(shaderVal == null) {
                            (shaderParam as FloatShaderParam).default
                        } else {
                            shaderVal as Float
                        }.format(2)
                    }
                    "color" -> {
                        val colorInt = if(shaderVal == null) {
                            (shaderParam as ColorShaderParam).default
                        } else {
                            (shaderVal as Int)
                        }
                        val color = Color.valueOf(colorInt)
                        "${color.red().format(2)}, ${color.green().format(2)}, ${color.blue().format(2)}"
                    }
                    "texture" -> {
                        val uriString = if (shaderVal == null) {
                            (shaderParam as TextureShaderParam).default!!
                        } else {
                            shaderVal as String
                        }
                        val scheme = Uri.parse(uriString).scheme ?: "unknown scheme"
                        "${uriString.split("/").last()} (${scheme})"
//                        uriString
//                        uriString.split("/").last()
                    }
                    else -> {
                        throw Exception("param type not handled in CameraActivity.updateShaderText")
                    }
                }
                paramHints += "\n  ${index + 1}. ${shaderParam.paramName} (${shaderValString})"
            }
            text += "\n Params: $paramHints"
        }
        if (shaderHasError) {
            text += "\n SHADER HAS ERROR\n\n $shaderErrorMsg"
        }
        shaderTitle.text = text
    }

    private fun fit(value: Float, oldMin: Float, oldMax: Float, newMin: Float, newMax: Float): Float {
        val inputRange: Float = oldMax - oldMin
        val outputRange: Float = newMax - newMin

        return (value - oldMin) * outputRange / inputRange + newMin
    }

    private fun useFallbackShader(): GenericShader {
        GenericShader.shaderAttributes = NoopShader
        GenericShader.context = this
        camera.filter = GenericShader()
        return camera.filter as GenericShader
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun handleShaderError (error: String): GenericShader {
        val shader = useFallbackShader()
        val uiContainer = findViewById<LinearLayout>(R.id.dynamic_ui)
        uiContainer.removeAllViews()
        shaderHasError = true
        shaderErrorMsg = error
        updateShaderText(shader.name)
        return shader
    }

//    @RequiresApi(Build.VERSION_CODES.O)
//    private fun buildShaderOrUseFallback(): GenericShader {
//        return try {
//            GenericShader()
//        } catch (e: Exception) {
//            handleShaderError(e.message ?: "Unknown shader error")
//        }
//    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun setShader(shaderAttributes: ShaderAttributes) {

        GenericShader.shaderAttributes = shaderAttributes
        GenericShader.errorCallback = { errorMessage: String ->
            runOnUiThread { handleShaderError(errorMessage) }
        }

//        shader = buildShaderOrUseFallback()
        shader = GenericShader()

        // Other errors do not prevent the shader from building,
        // and so they can be handled here.
        val error = validateShader(shader)
        if (error != null) {
            handleShaderError(error)
            return
        }

        shaderHasError = false
        shaderErrorMsg = null

        GenericShader.context = this
        camera.filter = shader

        val uiContainer = findViewById<LinearLayout>(R.id.dynamic_ui)
        uiContainer.removeAllViews()

        updateShaderText()

        val inflater =
            this.getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater

        shader.params.forEach { it ->
            when (it.paramType) {
                "float" -> {
                    val inflatedView = inflater.inflate(R.layout.param_slider, null)
                    uiContainer.addView(inflatedView)

                    val slider = inflatedView.findViewById<Slider>(R.id.slider)
                    val paramTitle = inflatedView.findViewById<TextView>(R.id.param_slider_name)
                    paramTitle.text = it.paramName

                    val shaderParam = it as FloatShaderParam
                    val shaderValue = (shader.dataValues[shaderParam.paramName] ?: shaderParam.default) as Float
                    val default01 = fit(shaderValue, shaderParam.min, shaderParam.max, 0.0f, 1.0f)

                    slider.value = default01

                    slider.addOnChangeListener { _, value, _ ->
                        val remappedVal = fit(value, 0.0f,1.0f, shaderParam.min, shaderParam.max)
                        updateShaderParam(it.paramName, remappedVal)
                    }
                }
                "color" -> {
                    val inflatedView = inflater.inflate(R.layout.param_color, null)
                    uiContainer.addView(inflatedView)
                    val button = inflatedView.findViewById<Button>(R.id.color_param_button)
                    val paramTitle = inflatedView.findViewById<TextView>(R.id.param_color_name)
                    paramTitle.text = it.paramName

                    val shaderParam = it as ColorShaderParam
                    val colorInt = (shader.dataValues[shaderParam.paramName] ?: shaderParam.default) as Int
                    ViewCompat.setBackgroundTintList(button, ColorStateList.valueOf(colorInt));

                    button.setOnClickListener { _ ->
                        CameraColorPickerActivity.startingColor = colorInt
                        CameraColorPickerActivity.paramName = it.paramName
                        val intent = Intent(this, CameraColorPickerActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        startActivity(intent)
                    }
                }
                "texture" -> {
                    val shaderParam = it as TextureShaderParam
                    val inflatedView = inflater.inflate(R.layout.param_texture, null)
                    uiContainer.addView(inflatedView)

                    val paramTitle = inflatedView.findViewById<TextView>(R.id.param_texture_name)
                    paramTitle.text = it.paramName

                    val imageView = inflatedView.findViewById<ImageView>(R.id.param_texture_preview)
                    val imageUriString = (shader.dataValues[shaderParam.paramName] ?: shaderParam.default) as String

                    val uri = Uri.parse(imageUriString)
                    val context = this
                    when (uri.scheme) {
                        "http", "https", "hardcodedResource" -> {
                            CoroutineScope(Dispatchers.IO).launch {
                                val bmp = TextureUtils.bitmapFromUri(context, uri)
                                runOnUiThread { imageView.setImageBitmap(bmp) }
                            }
                        }
                        else -> {
                            // It's likely a Uri from the user's photo gallery,
                            // which works fine to load from Uri (without parsing bitmap)
                            imageView.setImageURI(uri)
                        }
                    }

                    imageView.setOnClickListener {
                        throw Exception("Unhandled")
                    }
                }
                else -> {
                    throw Exception("unknown type")
                }
            }

        }
    }
}
