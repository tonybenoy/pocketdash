package com.tonybenoy.dashboard;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.Face;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Size;

import java.util.Collections;

/**
 * Tells the dashboard when somebody is looking at it, using the front camera's built-in face detector (the camera
 * hardware reports face rectangles in the capture results, so there is no image processing here at all).
 *
 * Frames are never read, stored or sent: the only output stream is a tiny dummy buffer that is discarded immediately.
 * Runs at the camera's lowest frame rate. Detection only fires for faces that are large enough (within a couple of
 * metres) and frontal (the detector does not report profile faces; when eye positions are available they are checked too).
 */
final class FaceWatcher {
    private static final String TAG = "FaceWatcher";
    private static final int CONFIRM_FRAMES = 3;          // consecutive frames with a face before "present"
    private static final long ABSENT_MS = 2000;           // no face for this long before "absent"
    private static final long RETRY_MS = 15000;           // camera busy / lost: try again later
    private static final float MIN_FACE_WIDTH = 0.06f;    // fraction of the sensor width: ignores faces far away

    interface Listener {
        void onPresence(boolean present);
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler h;
    private CameraDevice cam;
    private CameraCaptureSession session;
    private ImageReader reader;
    private Rect array;
    private boolean facePriority;
    private Size streamSize;
    // what the camera has been reporting, logged every few seconds
    private long lastSw, lastFullScan, lastSwHit, swSeenAt, lastLog;
    private int swRot = -1, swHits;
    private GazeDetector gaze;
    private boolean gazeFailed;
    private long lastGazeLog;
    private volatile boolean running;
    private boolean present;
    private int seenFrames;
    private long lastSeen;
    private volatile String status = "stopped";

    FaceWatcher(Context ctx, Listener l) {
        this.ctx = ctx.getApplicationContext();
        this.listener = l;
    }

    String status() { return status; }

    synchronized void start() {
        if (running) return;
        running = true;
        thread = new HandlerThread("face-watcher");
        thread.start();
        h = new Handler(thread.getLooper());
        h.post(this::open);
    }

    synchronized void stop() {
        if (!running) return;
        running = false;
        final HandlerThread t = thread;
        final Handler hh = h;
        hh.removeCallbacksAndMessages(null);
        hh.post(() -> { close(); t.quitSafely(); });
        thread = null;
        h = null;
        status = "stopped";
        if (present) { present = false; main.post(() -> listener.onPresence(false)); }
    }

    // ---------------------------------------------------------------- camera

    private void open() {
        if (!running) return;
        try {
            CameraManager cm = ctx.getSystemService(CameraManager.class);
            String id = null;
            int mode = CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_SIMPLE;
            Size size = null;
            Range<Integer> fps = null;
            for (String cid : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(cid);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_FRONT) continue;
                int[] modes = ch.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
                Integer maxFaces = ch.get(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT);
                if (modes == null || maxFaces == null || maxFaces <= 0) continue;
                boolean full = false, simple = false;
                for (int m : modes) { if (m == CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_FULL) full = true; if (m == CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_SIMPLE) simple = true; }
                if (!full && !simple) continue;
                mode = full ? CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_FULL : CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_SIMPLE;
                array = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                int[] scenes = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES);
                facePriority = false;
                if (scenes != null) for (int sm : scenes) if (sm == CameraCharacteristics.CONTROL_SCENE_MODE_FACE_PRIORITY) facePriority = true;
                StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map != null) for (Size s : map.getOutputSizes(ImageFormat.YUV_420_888)) {
                    long a = (long) s.getWidth() * s.getHeight();
                    boolean big = s.getWidth() >= 640 && s.getHeight() >= 480;
                    boolean curBig = size != null && size.getWidth() >= 640 && size.getHeight() >= 480;
                    // the smallest stream that is at least 640x480: the eyes need some detail
                    if (size == null || (big && !curBig) || (big == curBig && ((big && a < (long) size.getWidth() * size.getHeight())
                            || (!big && a > (long) size.getWidth() * size.getHeight())))) size = s;
                }
                Range<Integer>[] ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                if (ranges != null) for (Range<Integer> r : ranges) {
                    if (fps == null || r.getUpper() < fps.getUpper() || (r.getUpper().equals(fps.getUpper()) && r.getLower() < fps.getLower())) fps = r;   // slowest: we only sample a couple of times a second
                }
                id = cid;
                streamSize = size;
                break;
            }
            if (id == null || size == null || array == null) { setStatus("no front camera with face detection"); running = false; return; }

            final int faceMode = mode;
            final Range<Integer> fpsRange = fps;
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(r -> {
                Image img = r.acquireLatestImage();
                if (img == null) return;
                try {
                    long t = SystemClock.elapsedRealtime();
                    if (t - lastSw >= 500) { lastSw = t; softwareScan(img); }
                } finally {
                    img.close();
                }
            }, h);
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) { cam = d; startSession(faceMode, fpsRange); }
                @Override public void onDisconnected(CameraDevice d) { d.close(); lost("camera taken by another app"); }
                @Override public void onError(CameraDevice d, int e) { d.close(); lost("camera error " + e); }
            }, h);
        } catch (SecurityException e) {
            setStatus("camera permission missing");
            running = false;
        } catch (CameraAccessException | RuntimeException e) {
            lost("camera unavailable: " + e.getMessage());
        }
    }

    private void startSession(final int faceMode, final Range<Integer> fps) {
        try {
            cam.createCaptureSession(Collections.singletonList(reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    try {
                        CaptureRequest.Builder b = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        b.addTarget(reader.getSurface());
                        b.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, faceMode);
                        if (fps != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
                        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                        if (facePriority) {
                            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE);
                            b.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_FACE_PRIORITY);
                        }
                        s.setRepeatingRequest(b.build(), new CameraCaptureSession.CaptureCallback() {
                            @Override
                            public void onCaptureCompleted(CameraCaptureSession cs, CaptureRequest rq, TotalCaptureResult res) {
                                handle(res.get(CaptureResult.STATISTICS_FACES));
                            }
                        }, h);
                        setStatus("watching (" + (faceMode == CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_FULL ? "full" : "simple")
                            + " detection, " + (fps == null ? "?" : fps.toString()) + " fps)");
                    } catch (CameraAccessException | RuntimeException e) {
                        lost("could not start: " + e.getMessage());
                    }
                }

                @Override public void onConfigureFailed(CameraCaptureSession s) { lost("session failed"); }
            }, h);
        } catch (CameraAccessException | RuntimeException e) {
            lost("session error: " + e.getMessage());
        }
    }

    private void handle(Face[] faces) {
        long nowMs = SystemClock.elapsedRealtime();
        boolean seen = nowMs - swSeenAt < 1200;
        if (!seen && faces != null && array != null) {
            for (Face f : faces) {
                if (f.getScore() < 50) continue;
                Rect b = f.getBounds();
                if (b.width() < MIN_FACE_WIDTH * array.width()) continue;        // too far away
                Point le = f.getLeftEyePosition(), re = f.getRightEyePosition();
                if (le != null && re != null && Math.abs(re.x - le.x) / Math.max(1f, b.width()) < 0.28f) continue;   // turned away
                seen = true;
                break;
            }
        }
        long now = SystemClock.elapsedRealtime();
        if (seen) {
            lastSeen = now;
            if (++seenFrames >= CONFIRM_FRAMES && !present) {
                present = true;
                main.post(() -> listener.onPresence(true));
            }
        } else {
            seenFrames = 0;
            if (present && now - lastSeen > ABSENT_MS) {
                present = false;
                main.post(() -> listener.onPresence(false));
            }
        }
    }

    /** A face found by the software detector: which rotation of the frame it was found in, and where, in that rotated 160x120 frame. */
    private static final class Hit {
        int rot, frameWidth;
        android.graphics.PointF mid = new android.graphics.PointF();
        float eyes;
    }

    private GazeDetector gazeDetector() {
        if (gaze == null && !gazeFailed) {
            try { gaze = new GazeDetector(ctx); }
            catch (Exception | Error e) { gazeFailed = true; Log.w(TAG, "eye analysis unavailable, using face presence only: " + e); }
        }
        return gaze;
    }

    /**
     * Software face detection on a 160x120 grey copy of the frame (dropped immediately; nothing is stored or sent).
     * The camera's own detector only recognises upright faces, and a dashboard on a stand is rarely upright in the
     * sensor's frame, so the picture is tried at each of four rotations. The rotation that works is remembered and
     * tried first; all four are re-tried every few seconds, and whenever the remembered one fails.
     * When a face is found and "eyes only" is on, the eyes are analysed too and only "open and looking at the screen" counts.
     */
    private void softwareScan(Image img) {
        try {
            Image.Plane pl = img.getPlanes()[0];
            java.nio.ByteBuffer buf = pl.getBuffer();
            int rs = pl.getRowStride(), w = img.getWidth(), hh = img.getHeight();
            int step = Math.max(1, w / 160), dw = (w / step) & ~1, dh = (hh / step) & ~1;
            int[] px = new int[dw * dh];
            for (int y = 0; y < dh; y++) for (int x = 0; x < dw; x++) {
                int v = buf.get(y * step * rs + x * step) & 0xFF;
                px[y * dw + x] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
            android.graphics.Bitmap src = android.graphics.Bitmap.createBitmap(dw, dh, android.graphics.Bitmap.Config.RGB_565);
            src.setPixels(px, 0, dw, 0, 0, dw, dh);

            long now = SystemClock.elapsedRealtime();
            boolean full = swRot < 0 || now - lastFullScan > 6000;
            int[] order = full ? new int[]{swRot < 0 ? 0 : swRot, 90, 180, 270} : new int[]{swRot};
            Hit hit = null;
            for (int rot : order) {
                hit = detect(src, rot);
                if (hit != null) { swRot = rot; break; }
            }
            if (full) lastFullScan = now;
            else if (hit == null) lastFullScan = 0;      // the remembered rotation failed: look at all four next time

            boolean count = hit != null;
            String note = hit == null ? "no face" : "face in view";
            if (hit != null && prefsGaze()) {
                GazeDetector g = gazeDetector();
                if (g != null) {
                    GazeDetector.Result r = analyseEyes(g, img, hit);
                    count = r != null && r.looking;
                    note = r == null ? "face, eyes not measurable" : r.toString();
                    if (now - lastGazeLog > 1000) { lastGazeLog = now; Log.i("Gaze", note); }
                }
            }

            if (count) {
                swHits = now - lastSwHit < 1500 ? swHits + 1 : 1;     // two hits in a row before believing it
                lastSwHit = now;
                if (swHits >= 2) swSeenAt = now;
            }
            if (now - lastLog > 8000) {
                lastLog = now;
                Log.i(TAG, "software detector: rotation " + swRot + ", " + note + ", stream " + streamSize);
            }
        } catch (RuntimeException e) {
            Log.i(TAG, "software scan failed: " + e);
        }
    }

    private boolean prefsGaze() {
        return ctx.getSharedPreferences("dashboard", Context.MODE_PRIVATE).getBoolean("gaze", false);      // eye analysis is opt-in: slower, and stricter than "a face is looking this way"
    }

    private Hit detect(android.graphics.Bitmap src, int rot) {
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.postRotate(rot);
        android.graphics.Bitmap b = rot == 0 ? src : android.graphics.Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, false);
        if (b.getConfig() != android.graphics.Bitmap.Config.RGB_565) b = b.copy(android.graphics.Bitmap.Config.RGB_565, false);
        android.media.FaceDetector.Face[] fs = new android.media.FaceDetector.Face[2];
        int n = new android.media.FaceDetector(b.getWidth(), b.getHeight(), 2).findFaces(b, fs);
        for (int i = 0; i < n; i++) {
            if (fs[i] != null && fs[i].confidence() >= 0.4f && fs[i].eyesDistance() >= 0.05f * b.getWidth()) {   // near enough, and sure enough
                Hit h = new Hit();
                h.rot = rot;
                h.frameWidth = b.getWidth();
                fs[i].getMidPoint(h.mid);
                h.eyes = fs[i].eyesDistance();
                return h;
            }
        }
        return null;
    }

    /** Full-resolution colour frame, rotated the way the face was found, cropped around the face and measured. */
    private GazeDetector.Result analyseEyes(GazeDetector g, Image img, Hit hit) {
        android.graphics.Bitmap full = fullFrame(img, hit.rot);
        float scale = (float) full.getWidth() / hit.frameWidth;
        float eyes = hit.eyes * scale, cx = hit.mid.x * scale, cy = hit.mid.y * scale + 0.45f * eyes;
        GazeDetector.Result best = null;
        for (float k : new float[]{3.4f, 2.8f, 4.1f}) {            // the crop the model likes is a little bigger than the face
            android.graphics.Bitmap crop = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Matrix m = new android.graphics.Matrix();
            float side = k * eyes;
            m.postTranslate(-cx, -cy);
            m.postScale(256f / side, 256f / side);
            m.postTranslate(128f, 128f);
            new android.graphics.Canvas(crop).drawBitmap(full, m, new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
            GazeDetector.Result r = g.analyse(crop);
            crop.recycle();
            if (best == null || r.score > best.score) best = r;
            if (r.valid) return r;
        }
        return best != null && best.valid ? best : null;
    }

    /** YUV_420_888 to a colour bitmap at full resolution, rotated by rot degrees. */
    private android.graphics.Bitmap fullFrame(Image img, int rot) {
        int w = img.getWidth(), h = img.getHeight();
        Image.Plane yP = img.getPlanes()[0], uP = img.getPlanes()[1], vP = img.getPlanes()[2];
        java.nio.ByteBuffer yb = yP.getBuffer(), ub = uP.getBuffer(), vb = vP.getBuffer();
        int yrs = yP.getRowStride(), urs = uP.getRowStride(), ups = uP.getPixelStride(), vrs = vP.getRowStride(), vps = vP.getPixelStride();
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int Y = yb.get(y * yrs + x) & 0xFF;
                int U = (ub.get((y >> 1) * urs + (x >> 1) * ups) & 0xFF) - 128;
                int V = (vb.get((y >> 1) * vrs + (x >> 1) * vps) & 0xFF) - 128;
                int r = Y + (int) (1.402f * V), gg = Y - (int) (0.344f * U + 0.714f * V), b = Y + (int) (1.772f * U);
                r = r < 0 ? 0 : Math.min(r, 255);
                gg = gg < 0 ? 0 : Math.min(gg, 255);
                b = b < 0 ? 0 : Math.min(b, 255);
                px[y * w + x] = 0xFF000000 | (r << 16) | (gg << 8) | b;
            }
        }
        android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888);
        if (rot == 0) return bm;
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.postRotate(rot);
        return android.graphics.Bitmap.createBitmap(bm, 0, 0, w, h, m, true);
    }

    private void lost(String why) {
        setStatus(why);
        close();
        if (running && h != null) h.postDelayed(this::open, RETRY_MS);
    }

    private void close() {
        try { if (session != null) session.close(); } catch (RuntimeException ignored) { }
        try { if (cam != null) cam.close(); } catch (RuntimeException ignored) { }
        try { if (reader != null) reader.close(); } catch (RuntimeException ignored) { }
        if (gaze != null) { gaze.close(); gaze = null; }
        session = null;
        cam = null;
        reader = null;
    }

    private void setStatus(String s) {
        status = s;
        Log.i(TAG, s);
    }
}
