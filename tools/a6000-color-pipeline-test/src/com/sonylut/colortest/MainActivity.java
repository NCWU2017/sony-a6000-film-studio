package com.sonylut.colortest;

import android.app.Activity;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * A6000 SelectedColor revision capability/readback probe.
 *
 * No photo capture. The probe only:
 *  - reads supported modes and current mode
 *  - tries off / extract / revision
 *  - reads mode back through both ParametersModifier and raw Camera.Parameters
 *  - restores the original mode
 *
 * Results are displayed on screen and best-effort written to:
 *   /DCIM/SC_REVISION_PROBE.TXT
 *   /LUTS/SC_REVISION_PROBE.TXT
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "SCRevisionProbe";
    private static final int SCAN_MENU = 514;
    private static final int SCAN_DELETE = 595;

    private final Handler handler = new Handler();
    private SurfaceHolder holder;
    private TextView overlay;
    private Object cameraEx;
    private Camera normal;
    private boolean previewStarted;
    private boolean started;
    private String originalMode = "off";
    private int maxChannels = 0;
    private StringBuilder report = new StringBuilder();

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        SurfaceView sv = new SurfaceView(this);
        holder = sv.getHolder();
        holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        holder.addCallback(this);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        overlay = new TextView(this);
        overlay.setTextColor(0xffffffff);
        overlay.setBackgroundColor(0xcc000000);
        overlay.setTextSize(15);
        overlay.setGravity(Gravity.LEFT | Gravity.TOP);
        overlay.setPadding(10, 8, 10, 8);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2);
        lp.gravity = Gravity.TOP;
        root.addView(overlay, lp);
        setContentView(root);

        setText("Opening CameraEx...");
        openCamera();
    }

    private void openCamera() {
        try {
            Class<?> cx = Class.forName("com.sony.scalar.hardware.CameraEx");
            Method open = null;
            Method[] ms = cx.getMethods();
            for (int i=0;i<ms.length;i++) {
                if ("open".equals(ms[i].getName()) && ms[i].getParameterTypes().length==2) {
                    open = ms[i]; break;
                }
            }
            if (open == null) throw new NoSuchMethodException("CameraEx.open");
            cameraEx = open.invoke(null, new Object[]{Integer.valueOf(0), null});
            normal = (Camera) call(cameraEx, "getNormalCamera", new Class[0], new Object[0]);
            startPreviewIfReady();
            maybeStart();
        } catch(Throwable t) {
            add("OPEN ERROR: "+rootCause(t));
            finishReport();
        }
    }

    private Object modifier(Camera.Parameters p) throws Exception {
        return call(cameraEx, "createParametersModifier",
                new Class[]{Camera.Parameters.class}, new Object[]{p});
    }

    private void maybeStart() {
        if (started || normal==null || !previewStarted) return;
        started = true;
        handler.postDelayed(new Runnable() {
            public void run() { runProbe(); }
        }, 900);
    }

    private void runProbe() {
        try {
            add("=== A6000 SelectedColor revision probe v0.7 ===");

            Camera.Parameters p0 = normal.getParameters();
            Object m0 = modifier(p0);

            Object supported = call(m0, "getSupportedColorSelectModes", new Class[0], new Object[0]);
            originalMode = String.valueOf(call(m0, "getColorSelectMode", new Class[0], new Object[0]));
            maxChannels = ((Integer)call(m0, "getMaxColorSelectChannels", new Class[0], new Object[0])).intValue();

            add("SUPPORTED="+formatAny(supported));
            add("ORIGINAL_MODE="+originalMode);
            add("MAX_CHANNELS="+maxChannels);
            dumpRaw("BASE", p0);

            testMode("off", new int[0]);
            testMode("extract", maxChannels>0 ? new int[]{0} : new int[0]);
            testMode("revision", maxChannels>0 ? new int[]{0} : new int[0]);

            restoreOriginal();

            Camera.Parameters pr = normal.getParameters();
            Object mr = modifier(pr);
            add("RESTORED_MODE="+String.valueOf(call(mr, "getColorSelectMode", new Class[0], new Object[0])));
            dumpRaw("RESTORED", pr);
            add("=== COMPLETE ===");
        } catch(Throwable t) {
            add("PROBE ERROR: "+rootCause(t));
            try { restoreOriginal(); } catch(Throwable ignored) {}
        }

        finishReport();
    }

    private void testMode(String mode, int[] channels) {
        add("");
        add("--- TRY "+mode+" channels="+Arrays.toString(channels)+" ---");
        try {
            Camera.Parameters p = normal.getParameters();
            Object mod = modifier(p);
            call(mod, "setColorSelectMode",
                    new Class[]{String.class, int[].class},
                    new Object[]{mode, channels});
            add("SETTER_CALL=OK");
            normal.setParameters(p);
            add("Camera.setParameters=OK");
        } catch(Throwable t) {
            add("SET_ERROR="+rootCause(t));
        }

        try { Thread.sleep(350); } catch(InterruptedException ignored) {}

        try {
            Camera.Parameters after = normal.getParameters();
            Object modAfter = modifier(after);
            Object rb = call(modAfter, "getColorSelectMode", new Class[0], new Object[0]);
            Object sup = call(modAfter, "getSupportedColorSelectModes", new Class[0], new Object[0]);
            add("READBACK_MODE="+String.valueOf(rb));
            add("SUPPORTED_AFTER="+formatAny(sup));
            dumpRaw(mode.toUpperCase(), after);
        } catch(Throwable t) {
            add("READBACK_ERROR="+rootCause(t));
        }
    }

    private void dumpRaw(String tag, Camera.Parameters p) {
        String[] keys = new String[]{
            "color-select-mode",
            "color-select-mode-values",
            "color-select-max-channels",
            "color-select-channels",
            "color-select-channel",
            "color-select-supported"
        };
        for(int i=0;i<keys.length;i++) {
            String v=null;
            try { v=p.get(keys[i]); } catch(Throwable ignored) {}
            add(tag+"_RAW["+keys[i]+"]="+String.valueOf(v));
        }
    }

    private void restoreOriginal() throws Exception {
        if (normal==null) return;
        Camera.Parameters p=normal.getParameters();
        Object mod=modifier(p);
        int[] channels;
        if ("off".equals(originalMode)) {
            channels=new int[0];
        } else {
            int n=Math.max(0, Math.min(maxChannels, 2));
            channels=new int[n];
            for(int i=0;i<n;i++) channels[i]=i;
        }
        call(mod, "setColorSelectMode",
                new Class[]{String.class, int[].class},
                new Object[]{originalMode,channels});
        normal.setParameters(p);
    }

    private static String formatAny(Object o) {
        if (o==null) return "null";
        if (o instanceof String[]) return Arrays.toString((String[])o);
        if (o instanceof int[]) return Arrays.toString((int[])o);
        if (o instanceof Object[]) return Arrays.toString((Object[])o);
        return String.valueOf(o);
    }

    private void add(String s) {
        report.append(s).append('\n');
        Log.i(TAG,s);
        setText(report.toString());
    }

    private void finishReport() {
        writeReport(new File(new File(Environment.getExternalStorageDirectory(),"DCIM"),
                "SC_REVISION_PROBE.TXT"));
        File lut=new File(Environment.getExternalStorageDirectory(),"LUTS");
        if(!lut.exists()) lut.mkdirs();
        writeReport(new File(lut,"SC_REVISION_PROBE.TXT"));
        setText(report.toString()+"\nMENU to exit.");
    }

    private void writeReport(File f) {
        try {
            FileOutputStream os=new FileOutputStream(f,false);
            os.write(report.toString().getBytes("UTF-8"));
            os.close();
            Log.i(TAG,"WROTE "+f.getAbsolutePath());
        } catch(Throwable t) {
            Log.e(TAG,"write report failed "+f,t);
        }
    }

    private void setText(final String s) {
        if(overlay==null) return;
        overlay.setText(s);
    }

    @Override public void surfaceCreated(SurfaceHolder h) {
        startPreviewIfReady();
        maybeStart();
    }
    @Override public void surfaceChanged(SurfaceHolder h,int f,int w,int he) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) { previewStarted=false; }

    private void startPreviewIfReady() {
        if(normal==null || previewStarted || holder==null) return;
        try {
            normal.setPreviewDisplay(holder);
            normal.startPreview();
            previewStarted=true;
        } catch(Throwable t) {
            add("PREVIEW ERROR: "+rootCause(t));
            finishReport();
        }
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent e) {
        int scan=e.getScanCode();
        if(scan==SCAN_MENU || scan==SCAN_DELETE || keyCode==KeyEvent.KEYCODE_MENU ||
                keyCode==KeyEvent.KEYCODE_DEL) {
            try { restoreOriginal(); } catch(Throwable ignored) {}
            releaseCamera();
            finish();
            return true;
        }
        return true;
    }
    @Override public boolean onKeyUp(int keyCode,KeyEvent e) { return true; }

    private void releaseCamera() {
        try { if(normal!=null && previewStarted) normal.stopPreview(); } catch(Throwable ignored) {}
        try { if(cameraEx!=null) call(cameraEx,"release",new Class[0],new Object[0]); } catch(Throwable ignored) {}
        normal=null; cameraEx=null; previewStarted=false;
    }

    @Override protected void onPause() {
        super.onPause();
        if(isFinishing()) {
            try { restoreOriginal(); } catch(Throwable ignored) {}
            releaseCamera();
        }
    }

    private static Object call(Object o,String n,Class[] types,Object[] args) throws Exception {
        Method m=o.getClass().getMethod(n,types);
        return m.invoke(o,args);
    }

    private static String rootCause(Throwable t) {
        Throwable c=t;
        while(c.getCause()!=null) c=c.getCause();
        return c.getClass().getSimpleName()+": "+String.valueOf(c.getMessage());
    }
}
