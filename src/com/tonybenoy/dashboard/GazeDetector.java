package com.tonybenoy.dashboard;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.util.Log;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Map;

/**
 * Are the eyes open and pointed at the screen? Runs Google's open face-landmark model (478 points, including the
 * irises) on a 256x256 crop of the face and reduces the points to a few ratios that do not depend on image size:
 *
 *   openness  eyelid gap / eye width, per eye (open about 0.25-0.35, closed below about 0.15)
 *   yaw       where the nose tip sits between the cheeks (0.5 = facing straight at the camera)
 *   gaze      where the iris centre sits between the eye corners (0.5 = looking straight ahead) and between the lids
 *
 * The picture is analysed in memory and thrown away.
 */
final class GazeDetector {
    private static final String TAG = "Gaze";

    static final class Result {
        boolean valid;           // the model found a face in the crop
        float score;
        float earL, earR;        // eye openness
        float yaw;               // 0.5 = frontal
        float gazeX, gazeY;      // mean iris position, 0.5 = centred
        boolean eyesOpen, looking;

        @Override
        public String toString() {
            return String.format("score=%.2f open=%.2f/%.2f yaw=%.2f gaze=%.2f,%.2f -> %s", score, earL, earR, yaw, gazeX, gazeY,
                !valid ? "no face in crop" : looking ? "LOOKING" : eyesOpen ? "eyes open, not looking at screen" : "eyes closed");
        }
    }

    // MediaPipe face mesh indices
    private static final int R_OUT = 33, R_IN = 133, R_UP = 159, R_LOW = 145, R_IRIS = 468;
    private static final int L_OUT = 263, L_IN = 362, L_UP = 386, L_LOW = 374, L_IRIS = 473;
    private static final int NOSE = 1, CHEEK_R = 234, CHEEK_L = 454;

    // thresholds (tuned from logs of real use)
    static final float EAR_OPEN = 0.17f;
    static final float YAW_TOL = 0.20f;
    static final float GAZE_X_LO = 0.30f, GAZE_X_HI = 0.70f, GAZE_Y_LO = 0.20f, GAZE_Y_HI = 0.85f;

    private Interpreter interp;
    private int lmIndex = -1, scoreIndex = -1, lmCount;
    private final ByteBuffer in = ByteBuffer.allocateDirect(256 * 256 * 3 * 4).order(ByteOrder.nativeOrder());
    private final int[] px = new int[256 * 256];
    private final Map<Integer, Object> outs = new HashMap<>();
    private ByteBuffer[] outBufs;

    GazeDetector(Context ctx) throws Exception {
        AssetFileDescriptor fd = ctx.getAssets().openFd("gaze/face_landmarks.tflite");
        MappedByteBuffer model;
        try (FileInputStream f = new FileInputStream(fd.getFileDescriptor())) {
            model = f.getChannel().map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
        }
        Interpreter.Options o = new Interpreter.Options();
        o.setNumThreads(2);
        interp = new Interpreter(model, o);

        StringBuilder sb = new StringBuilder("model: input " + java.util.Arrays.toString(interp.getInputTensor(0).shape())
            + " " + interp.getInputTensor(0).dataType());
        int n = interp.getOutputTensorCount();
        outBufs = new ByteBuffer[n];
        for (int i = 0; i < n; i++) {
            int[] sh = interp.getOutputTensor(i).shape();
            int count = 1;
            for (int d : sh) count *= d;
            DataType dt = interp.getOutputTensor(i).dataType();
            sb.append("; out").append(i).append(" ").append(java.util.Arrays.toString(sh)).append(" ").append(dt);
            outBufs[i] = ByteBuffer.allocateDirect(4 * count).order(ByteOrder.nativeOrder());
            if (count == 1434 || count == 1404) { lmIndex = i; lmCount = count / 3; }
            if (count == 1 && sh.length == 4 && scoreIndex < 0) scoreIndex = i;     // the [1,1,1,1] output is the face-presence score
        }
        Log.i(TAG, sb.toString() + "  -> landmarks=" + lmIndex + " (" + lmCount + " points) score=" + scoreIndex);
        if (lmIndex < 0) throw new IllegalStateException("unexpected model outputs");
    }

    /** @param face256 a 256x256 crop with the face roughly in the middle, upright (eyes on a horizontal line) */
    synchronized Result analyse(Bitmap face256) {
        Result r = new Result();
        face256.getPixels(px, 0, 256, 0, 0, 256, 256);
        in.rewind();
        for (int p : px) {
            in.putFloat(((p >> 16) & 0xFF) / 255f);
            in.putFloat(((p >> 8) & 0xFF) / 255f);
            in.putFloat((p & 0xFF) / 255f);
        }
        in.rewind();
        outs.clear();
        for (int i = 0; i < outBufs.length; i++) { outBufs[i].rewind(); outs.put(i, outBufs[i]); }
        interp.runForMultipleInputsOutputs(new Object[]{in}, outs);

        outBufs[scoreIndex < 0 ? lmIndex : scoreIndex].rewind();
        r.score = scoreIndex >= 0 ? outBufs[scoreIndex].getFloat(0) : 1f;
        r.valid = r.score > 0.5f || (r.score < 0f || r.score > 1f) && sigmoid(r.score) > 0.5f;   // some builds output a raw logit
        float[] lm = new float[lmCount * 3];
        outBufs[lmIndex].rewind();
        outBufs[lmIndex].asFloatBuffer().get(lm);
        if (!r.valid || lmCount < 478) return r;

        r.earR = dist(lm, R_UP, R_LOW) / Math.max(1e-3f, dist(lm, R_OUT, R_IN));
        r.earL = dist(lm, L_UP, L_LOW) / Math.max(1e-3f, dist(lm, L_OUT, L_IN));
        r.yaw = along(lm, NOSE, CHEEK_R, CHEEK_L);
        float gxR = along(lm, R_IRIS, R_OUT, R_IN), gxL = along(lm, L_IRIS, L_OUT, L_IN);
        float gyR = along(lm, R_IRIS, R_UP, R_LOW), gyL = along(lm, L_IRIS, L_UP, L_LOW);
        r.gazeX = (gxR + gxL) / 2f;
        r.gazeY = (gyR + gyL) / 2f;
        r.eyesOpen = r.earL >= EAR_OPEN && r.earR >= EAR_OPEN;
        r.looking = r.eyesOpen && Math.abs(r.yaw - 0.5f) <= YAW_TOL
            && r.gazeX >= GAZE_X_LO && r.gazeX <= GAZE_X_HI && r.gazeY >= GAZE_Y_LO && r.gazeY <= GAZE_Y_HI;
        return r;
    }

    private static float sigmoid(float x) { return 1f / (1f + (float) Math.exp(-x)); }

    private static float dist(float[] lm, int a, int b) {
        float dx = lm[a * 3] - lm[b * 3], dy = lm[a * 3 + 1] - lm[b * 3 + 1];
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** Position of point p along the line from a to b, as a fraction (0 at a, 1 at b). Independent of roll and scale. */
    private static float along(float[] lm, int p, int a, int b) {
        float ax = lm[a * 3], ay = lm[a * 3 + 1];
        float bx = lm[b * 3] - ax, by = lm[b * 3 + 1] - ay;
        float px = lm[p * 3] - ax, py = lm[p * 3 + 1] - ay;
        float len2 = bx * bx + by * by;
        return len2 < 1e-6f ? 0.5f : (px * bx + py * by) / len2;
    }

    void close() {
        if (interp != null) { interp.close(); interp = null; }
    }
}
