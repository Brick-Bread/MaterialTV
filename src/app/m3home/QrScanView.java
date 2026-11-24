package app.m3home;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

/**
 * A camera preview that reads QR codes (Camera2 + ZXing, no other libraries). Calls back once with
 * the text of the first code it reads. The caller must already hold the CAMERA permission.
 */
final class QrScanView extends TextureView {
    interface Found { void on(String text); }

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler bg;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private Found found;
    private volatile boolean done;
    private final QRCodeReader qr = new QRCodeReader();
    private final Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);

    QrScanView(Context c) {
        super(c);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
    }

    void start(Found f) {
        found = f;
        done = false;
        thread = new HandlerThread("m3home-qr");
        thread.start();
        bg = new Handler(thread.getLooper());
        if (isAvailable()) open();
        else setSurfaceTextureListener(new SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { open(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture s) { }
        });
    }

    void stop() {
        done = true;
        try { if (session != null) session.close(); } catch (RuntimeException ignored) { }
        try { if (camera != null) camera.close(); } catch (RuntimeException ignored) { }
        try { if (reader != null) reader.close(); } catch (RuntimeException ignored) { }
        session = null;
        camera = null;
        reader = null;
        if (thread != null) { thread.quitSafely(); thread = null; }
    }

    @SuppressLint("MissingPermission")
    private void open() {
        try {
            CameraManager cm = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
            String id = null;
            for (String c : cm.getCameraIdList()) {
                Integer facing = cm.getCameraCharacteristics(c).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { id = c; break; }
                if (id == null) id = c;
            }
            if (id == null) return;
            StreamConfigurationMap map = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size size = pick(map == null ? null : map.getOutputSizes(ImageFormat.YUV_420_888));
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(this::frame, bg);
            SurfaceTexture st = getSurfaceTexture();
            st.setDefaultBufferSize(size.getWidth(), size.getHeight());
            final Surface preview = new Surface(st);
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) {
                    camera = d;
                    try {
                        final CaptureRequest.Builder rb = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        rb.addTarget(preview);
                        rb.addTarget(reader.getSurface());
                        rb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        d.createCaptureSession(Arrays.asList(preview, reader.getSurface()), new CameraCaptureSession.StateCallback() {
                            @Override public void onConfigured(CameraCaptureSession s) {
                                session = s;
                                try { s.setRepeatingRequest(rb.build(), null, bg); } catch (CameraAccessException | RuntimeException ignored) { }
                            }
                            @Override public void onConfigureFailed(CameraCaptureSession s) { }
                        }, bg);
                    } catch (CameraAccessException | RuntimeException ignored) { }
                }
                @Override public void onDisconnected(CameraDevice d) { d.close(); }
                @Override public void onError(CameraDevice d, int e) { d.close(); }
            }, bg);
        } catch (CameraAccessException | RuntimeException ignored) { }
    }

    /** A modest size (about 1280x720) decodes quickly and is plenty for a QR code on a TV. */
    private static Size pick(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return new Size(1280, 720);
        Size best = sizes[0];
        for (Size s : sizes) {
            long area = (long) s.getWidth() * s.getHeight();
            long bestDiff = Math.abs((long) best.getWidth() * best.getHeight() - 1280L * 720);
            if (Math.abs(area - 1280L * 720) < bestDiff) best = s;
        }
        return best;
    }

    private void frame(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null || done) return;
            Image.Plane y = img.getPlanes()[0];
            ByteBuffer buf = y.getBuffer();
            int w = img.getWidth(), h = img.getHeight(), stride = y.getRowStride();
            byte[] data = new byte[stride * h];
            buf.get(data, 0, Math.min(data.length, buf.remaining()));
            PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(data, stride, h, 0, 0, w, h, false);
            Result res = qr.decode(new BinaryBitmap(new HybridBinarizer(src)), hints);
            if (res != null && !done) {
                done = true;
                final String text = res.getText();
                main.post(() -> { if (found != null) found.on(text); });
            }
        } catch (Exception ignored) {
            // no code in this frame
        } finally {
            if (img != null) img.close();
            qr.reset();
        }
    }
}
