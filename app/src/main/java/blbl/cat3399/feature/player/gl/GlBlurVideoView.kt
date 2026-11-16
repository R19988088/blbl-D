package blbl.cat3399.feature.player.gl

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt

/**
 * GL video surface used by the player when the real-time backdrop blur is enabled.
 *
 * Decoder frames arrive through a [SurfaceTexture] (external OES texture). The renderer draws the
 * video with aspect-fit, and when a blur region is enabled it also renders a downscaled copy of the
 * current frame through a two-pass separable Gaussian before compositing it over the requested
 * bottom region. Rendering is driven by frame availability (RENDERMODE_WHEN_DIRTY), so a paused
 * player costs nothing and a playing one updates at the video's own frame rate.
 */
class GlBlurVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    private val renderer = BlurRenderer()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Invoked on the main thread whenever a new Surface is ready to receive decoder frames. */
    var onSurfaceReady: ((Surface) -> Unit)? = null

    /** Invoked on the main thread when the previous Surface is no longer valid (GL context lost). */
    var onSurfaceLost: (() -> Unit)? = null

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        holder.setFormat(PixelFormat.RGBA_8888)
        preserveEGLContextOnPause = true
        setZOrderMediaOverlay(true)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (renderer.videoWidth == width && renderer.videoHeight == height) return
        renderer.videoWidth = width
        renderer.videoHeight = height
        queueEvent { renderer.applyVideoBufferSize() }
        requestRender()
    }

    /**
     * Blurs the region from [topFraction] (0 = top, 1 = bottom) to the bottom edge.
     * Passing enabled=false or topFraction=1 disables the blurred composite.
     */
    fun setBlurRegion(topFraction: Float, enabled: Boolean) {
        val fraction = topFraction.coerceIn(0f, 1f)
        if (renderer.blurEnabled == enabled && renderer.blurTopFraction == fraction) return
        renderer.blurEnabled = enabled
        renderer.blurTopFraction = fraction
        requestRender()
    }

    fun currentSurface(): Surface? = renderer.outputSurface

    fun releaseGl() {
        // Release the decoder-facing Surface on the main thread as well: the GL thread may already
        // be paused when the activity is destroyed, in which case queued events never run.
        renderer.takeSurfaceForRelease()?.release()
        queueEvent { renderer.releaseSurface() }
    }

    override fun onPause() {
        Log.i(TAG, "view:onPause")
        super.onPause()
    }

    override fun onResume() {
        Log.i(TAG, "view:onResume")
        super.onResume()
    }

    override fun onDetachedFromWindow() {
        Log.i(TAG, "view:onDetachedFromWindow")
        super.onDetachedFromWindow()
    }

    private inner class BlurRenderer : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

        @Volatile
        var videoWidth: Int = 0

        @Volatile
        var videoHeight: Int = 0

        @Volatile
        var blurEnabled: Boolean = false

        @Volatile
        var blurTopFraction: Float = 0f

        @Volatile
        var outputSurface: Surface? = null
            private set

        private var surfaceTexture: SurfaceTexture? = null
        private var oesTextureId = 0

        private var frameAvailable = false
        private var hasFrame = false
        private var frameCallbackCount = 0
        private var drawFrameCount = 0

        private var viewWidth = 0
        private var viewHeight = 0

        private var videoProgram = 0
        private var blurProgram = 0

        private var videoPosLocation = -1
        private var videoTexLocation = -1
        private var videoMatrixLocation = -1
        private var videoSamplerLocation = -1

        private var blurPosLocation = -1
        private var blurTexLocation = -1
        private var blurSamplerLocation = -1
        private var blurStepLocation = -1

        private val texMatrix = FloatArray(16)
        private val quad = FloatArray(16)
        private val fullQuad = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )

        private val quadBuffer: FloatBuffer =
            ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        private val fullQuadBuffer: FloatBuffer =
            ByteBuffer.allocateDirect(fullQuad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(fullQuad)
                position(0)
            }

        private var fboWidth = 0
        private var fboHeight = 0
        private var fboA = 0
        private var fboB = 0
        private var textureA = 0
        private var textureB = 0

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            Log.i(TAG, "renderer:onSurfaceCreated hasOld=${surfaceTexture != null}")
            if (surfaceTexture != null) {
                mainHandler.post { onSurfaceLost?.invoke() }
            }
            releaseSurfaceTexture()

            videoProgram = buildProgram(VERTEX_VIDEO, FRAGMENT_VIDEO)
            blurProgram = buildProgram(VERTEX_BLUR, FRAGMENT_BLUR)
            if (videoProgram == 0 || blurProgram == 0) {
                Log.e(TAG, "Unable to create GL programs; video output disabled")
                return
            }

            videoPosLocation = GLES20.glGetAttribLocation(videoProgram, "aPosition")
            videoTexLocation = GLES20.glGetAttribLocation(videoProgram, "aTexCoord")
            videoMatrixLocation = GLES20.glGetUniformLocation(videoProgram, "uTexMatrix")
            videoSamplerLocation = GLES20.glGetUniformLocation(videoProgram, "uTexture")

            blurPosLocation = GLES20.glGetAttribLocation(blurProgram, "aPosition")
            blurTexLocation = GLES20.glGetAttribLocation(blurProgram, "aTexCoord")
            blurSamplerLocation = GLES20.glGetUniformLocation(blurProgram, "uTexture")
            blurStepLocation = GLES20.glGetUniformLocation(blurProgram, "uTexelStep")

            oesTextureId = createExternalTexture()

            val surfaceTexture = SurfaceTexture(oesTextureId).apply {
                // A fixed, small geometry keeps the blur pass cheap. The GL view is only
                // used while controls are visible, so the decoder is not starved by it.
                setDefaultBufferSize(FIXED_BUFFER_WIDTH, FIXED_BUFFER_HEIGHT)
                setOnFrameAvailableListener(this@BlurRenderer)
            }
            this.surfaceTexture = surfaceTexture

            val surface = Surface(surfaceTexture)
            outputSurface = surface
            mainHandler.post { onSurfaceReady?.invoke(surface) }

            hasFrame = false
            frameAvailable = false
            GLES20.glClearColor(0f, 0f, 0f, 1f)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewWidth = width
            viewHeight = height
            releaseBlurTargets()
        }

        override fun onDrawFrame(gl: GL10?) {
            val surfaceTexture = surfaceTexture ?: return

            if (frameAvailable) {
                try {
                    surfaceTexture.updateTexImage()
                    surfaceTexture.getTransformMatrix(texMatrix)
                    frameAvailable = false
                    hasFrame = true
                } catch (t: Throwable) {
                    if (drawFrameCount < 3) Log.w(TAG, "updateTexImage failed", t)
                }
            }

            drawFrameCount++
            if (drawFrameCount <= 5 || drawFrameCount % 60 == 0) {
                Log.i(TAG, "renderer:draw n=$drawFrameCount frames=$frameCallbackCount hasFrame=$hasFrame ts=${surfaceTexture.timestamp}")
            }

            GLES20.glViewport(0, 0, viewWidth, viewHeight)
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            if (!hasFrame) return

            drawVideoToScreen()

            if (blurEnabled && blurTopFraction < 1f) {
                drawBlurredRegion()
            }
        }

        override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
            frameAvailable = true
            frameCallbackCount++
            if (frameCallbackCount <= 5 || frameCallbackCount % 30 == 0) {
                Log.i(TAG, "renderer:frameAvailable n=$frameCallbackCount")
            }
            requestRender()
        }

        fun applyVideoBufferSize() {
            if (videoWidth <= 0 || videoHeight <= 0) return
            Log.i(TAG, "renderer:videoSize ${videoWidth}x${videoHeight}")
        }

        fun takeSurfaceForRelease(): Surface? {
            val surface = outputSurface ?: return null
            outputSurface = null
            return surface
        }

        fun releaseSurface() {
            releaseSurfaceTexture()
            releaseBlurTargets()
            hasFrame = false
            frameAvailable = false
        }

        private fun drawVideoToScreen() {
            updateVideoQuad(viewWidth, viewHeight)
            GLES20.glUseProgram(videoProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            GLES20.glUniform1i(videoSamplerLocation, 0)
            GLES20.glUniformMatrix4fv(videoMatrixLocation, 1, false, texMatrix, 0)
            drawQuad(videoProgram, videoPosLocation, videoTexLocation, quadBuffer)
        }

        private fun drawVideoInto(frameWidth: Int, frameHeight: Int) {
            updateVideoQuad(frameWidth, frameHeight)
            GLES20.glUseProgram(videoProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            GLES20.glUniform1i(videoSamplerLocation, 0)
            GLES20.glUniformMatrix4fv(videoMatrixLocation, 1, false, texMatrix, 0)
            drawQuad(videoProgram, videoPosLocation, videoTexLocation, quadBuffer)
        }

        private fun drawBlurredRegion() {
            if (viewWidth <= 0 || viewHeight <= 0) return
            ensureBlurTargets()

            // Pass 0: render the video (aspect-fit) into the downscaled target.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboA)
            GLES20.glViewport(0, 0, fboWidth, fboHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            drawVideoInto(fboWidth, fboHeight)

            // Pass 1: horizontal Gaussian.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboB)
            GLES20.glViewport(0, 0, fboWidth, fboHeight)
            GLES20.glUseProgram(blurProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureA)
            GLES20.glUniform1i(blurSamplerLocation, 0)
            GLES20.glUniform2f(blurStepLocation, 1f / fboWidth.toFloat(), 0f)
            drawQuad(blurProgram, blurPosLocation, blurTexLocation, fullQuadBuffer)

            // Pass 2: vertical Gaussian.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboA)
            GLES20.glViewport(0, 0, fboWidth, fboHeight)
            GLES20.glUseProgram(blurProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureB)
            GLES20.glUniform1i(blurSamplerLocation, 0)
            GLES20.glUniform2f(blurStepLocation, 0f, 1f / fboHeight.toFloat())
            drawQuad(blurProgram, blurPosLocation, blurTexLocation, fullQuadBuffer)

            // Composite only the requested bottom region over the normal video.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, viewWidth, viewHeight)
            val regionHeight = (viewHeight * (1f - blurTopFraction)).roundToInt().coerceIn(0, viewHeight)
            if (regionHeight <= 0) return

            GLES20.glUseProgram(blurProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureA)
            GLES20.glUniform1i(blurSamplerLocation, 0)
            GLES20.glUniform2f(blurStepLocation, 0f, 0f)

            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
            GLES20.glScissor(0, 0, viewWidth, regionHeight)
            drawQuad(blurProgram, blurPosLocation, blurTexLocation, fullQuadBuffer)
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        }

        private fun updateVideoQuad(width: Int, height: Int) {
            val vw = videoWidth
            val vh = videoHeight
            val halfX: Float
            val halfY: Float
            if (vw <= 0 || vh <= 0 || width <= 0 || height <= 0) {
                halfX = 1f
                halfY = 1f
            } else {
                val viewAspect = width.toFloat() / height.toFloat()
                val videoAspect = vw.toFloat() / vh.toFloat()
                if (videoAspect > viewAspect) {
                    halfX = 1f
                    halfY = (viewAspect / videoAspect).coerceIn(0f, 1f)
                } else {
                    halfX = (videoAspect / viewAspect).coerceIn(0f, 1f)
                    halfY = 1f
                }
            }

            quad[0] = -halfX
            quad[1] = -halfY
            quad[2] = 0f
            quad[3] = 0f

            quad[4] = halfX
            quad[5] = -halfY
            quad[6] = 1f
            quad[7] = 0f

            quad[8] = -halfX
            quad[9] = halfY
            quad[10] = 0f
            quad[11] = 1f

            quad[12] = halfX
            quad[13] = halfY
            quad[14] = 1f
            quad[15] = 1f

            quadBuffer.clear()
            quadBuffer.put(quad)
            quadBuffer.position(0)
        }

        private fun drawQuad(program: Int, positionLocation: Int, texCoordLocation: Int, buffer: FloatBuffer) {
            if (positionLocation < 0 || texCoordLocation < 0) return
            buffer.position(0)
            GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 16, buffer)
            GLES20.glEnableVertexAttribArray(positionLocation)
            buffer.position(2)
            GLES20.glVertexAttribPointer(texCoordLocation, 2, GLES20.GL_FLOAT, false, 16, buffer)
            GLES20.glEnableVertexAttribArray(texCoordLocation)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(positionLocation)
            GLES20.glDisableVertexAttribArray(texCoordLocation)
        }

        private fun ensureBlurTargets() {
            val targetWidth = (viewWidth / BLUR_DOWNSCALE).coerceIn(MIN_BLUR_WIDTH, MAX_BLUR_WIDTH)
            val targetHeight =
                (targetWidth.toFloat() * viewHeight.toFloat() / viewWidth.toFloat())
                    .roundToInt()
                    .coerceIn(MIN_BLUR_HEIGHT, MAX_BLUR_HEIGHT)
            if (fboA != 0 && fboB != 0 && fboWidth == targetWidth && fboHeight == targetHeight) return

            releaseBlurTargets()
            fboWidth = targetWidth
            fboHeight = targetHeight
            textureA = createTexture2D(fboWidth, fboHeight)
            fboA = createFramebuffer(textureA)
            textureB = createTexture2D(fboWidth, fboHeight)
            fboB = createFramebuffer(textureB)
        }

        private fun releaseBlurTargets() {
            if (fboA != 0) {
                GLES20.glDeleteFramebuffers(1, intArrayOf(fboA), 0)
                fboA = 0
            }
            if (fboB != 0) {
                GLES20.glDeleteFramebuffers(1, intArrayOf(fboB), 0)
                fboB = 0
            }
            if (textureA != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(textureA), 0)
                textureA = 0
            }
            if (textureB != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(textureB), 0)
                textureB = 0
            }
            fboWidth = 0
            fboHeight = 0
        }

        private fun releaseSurfaceTexture() {
            Log.i(TAG, "renderer:releaseSurfaceTexture")
            surfaceTexture?.setOnFrameAvailableListener(null)
            outputSurface?.release()
            outputSurface = null
            surfaceTexture?.release()
            surfaceTexture = null
            if (oesTextureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
                oesTextureId = 0
            }
        }

        private fun createExternalTexture(): Int {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            val id = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE,
            )
            return id
        }

        private fun createTexture2D(width: Int, height: Int): Int {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            val id = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_RGBA,
                width,
                height,
                0,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                null,
            )
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            return id
        }

        private fun createFramebuffer(textureId: Int): Int {
            val ids = IntArray(1)
            GLES20.glGenFramebuffers(1, ids, 0)
            val id = ids[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, id)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,
                textureId,
                0,
            )
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                Log.e(TAG, "Incomplete framebuffer: 0x${Integer.toHexString(status)}")
            }
            return id
        }

        private fun buildProgram(vertexSource: String, fragmentSource: String): Int {
            val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
            val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            if (vertexShader == 0 || fragmentShader == 0) return 0

            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            if (status[0] == 0) {
                Log.e(TAG, "Program link failed: ${GLES20.glGetProgramInfoLog(program)}")
                GLES20.glDeleteProgram(program)
                return 0
            }
            return program
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                Log.e(TAG, "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
                GLES20.glDeleteShader(shader)
                return 0
            }
            return shader
        }
    }

    private companion object {
        private const val TAG = "GlBlurVideoView"
private const val FIXED_BUFFER_WIDTH = 852
private const val FIXED_BUFFER_HEIGHT = 480
        private const val BLUR_DOWNSCALE = 6
        private const val MIN_BLUR_WIDTH = 64
        private const val MAX_BLUR_WIDTH = 480
        private const val MIN_BLUR_HEIGHT = 36
        private const val MAX_BLUR_HEIGHT = 270

        private val VERTEX_VIDEO =
            """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
            """.trimIndent()

        private val FRAGMENT_VIDEO =
            """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
            """.trimIndent()

        private val VERTEX_BLUR =
            """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
            """.trimIndent()

        // 5-tap linear-sampled Gaussian (equivalent to a 9-tap kernel), weights sum to 1.
        private val FRAGMENT_BLUR =
            """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec2 uTexelStep;
            varying vec2 vTexCoord;
            void main() {
                vec4 color = texture2D(uTexture, vTexCoord) * 0.2270270270;
                color += texture2D(uTexture, vTexCoord + uTexelStep * 1.3846153846) * 0.3162162162;
                color += texture2D(uTexture, vTexCoord - uTexelStep * 1.3846153846) * 0.3162162162;
                color += texture2D(uTexture, vTexCoord + uTexelStep * 3.2307692308) * 0.0702702703;
                color += texture2D(uTexture, vTexCoord - uTexelStep * 3.2307692308) * 0.0702702703;
                gl_FragColor = color;
            }
            """.trimIndent()
    }
}
