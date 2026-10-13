package org.parallelnest.probe

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/** One real Camera2 JPEG request. No persisted image, fabricated frame or background capture. */
@SuppressLint("MissingPermission")
suspend fun cameraCapture(context: Context,front: Boolean): ByteArray {
    val manager=context.getSystemService(CameraManager::class.java)
    val facing=if(front)CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
    val id=manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)==facing } ?: error("This camera is not exposed by Android")
    val supported=manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.JPEG).orEmpty()
    val size=supported.minByOrNull { it.width.toLong()*it.height } ?: error("Camera does not expose JPEG capture")
    val thread=HandlerThread("probe-camera").apply { start() }
    val handler=Handler(thread.looper)
    val result=CompletableDeferred<ByteArray>()
    var camera: CameraDevice?=null
    var session: CameraCaptureSession?=null
    val reader=ImageReader.newInstance(size.width,size.height,ImageFormat.JPEG,1)
    reader.setOnImageAvailableListener({ source ->
        try { source.acquireNextImage()?.use { image -> val buffer=image.planes[0].buffer;require(buffer.remaining()<=8_388_608){"Capture is too large"};val bytes=ByteArray(buffer.remaining());buffer.get(bytes);result.complete(bytes) } }
        catch(e:Exception){result.completeExceptionally(e)}
    },handler)
    try {
        manager.openCamera(id,object: CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if(result.isCancelled){device.close();return}
                camera=device
                try {
                    @Suppress("DEPRECATION")
                    device.createCaptureSession(listOf(reader.surface),object:CameraCaptureSession.StateCallback(){
                        override fun onConfigured(configured: CameraCaptureSession) {
                            session=configured
                            try {
                                val request=device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                    addTarget(reader.surface)
                                    if(manager.getCameraCharacteristics(id).get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)==true)set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                }.build()
                                configured.capture(request,object:CameraCaptureSession.CaptureCallback(){override fun onCaptureFailed(s:CameraCaptureSession,r:CaptureRequest,f:CaptureFailure){result.completeExceptionally(IllegalStateException("Android capture failed: ${f.reason}"))}},handler)
                            }catch(e:Exception){result.completeExceptionally(e)}
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession){result.completeExceptionally(IllegalStateException("Android could not configure capture"))}
                    },handler)
                }catch(e:Exception){result.completeExceptionally(e)}
            }
            override fun onDisconnected(device: CameraDevice){device.close();result.completeExceptionally(IllegalStateException("Camera disconnected or busy"))}
            override fun onError(device: CameraDevice,error: Int){device.close();result.completeExceptionally(IllegalStateException("Android camera error $error; check privacy controls and concurrent camera use"))}
        },handler)
        return withTimeout(12_000){result.await()}
    } finally { result.cancel();session?.close();camera?.close();reader.close();thread.quitSafely() }
}
