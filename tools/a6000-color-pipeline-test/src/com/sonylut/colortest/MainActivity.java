package com.sonylut.colortest;

import android.app.Activity;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Environment;
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
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Sony A6000 color-pipeline calibration probe.
 *
 * No Sony compile-time stubs are required: CameraEx/ParametersModifier/SelectedColor
 * are accessed through reflection so this project can be built with a stock Android SDK.
 *
 * Controls:
 * LEFT / dial1 CCW  previous step
 * RIGHT / dial1 CW next step
 * CENTER            apply current step
 * S1                autofocus
 * S2                capture JPEG to /DCIM/COLORTEST
 * DELETE            restore original parameters
 * MENU              restore, release camera, exit
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "A6000ColorTest";
    private static final int SCAN_MENU = 514;
    private static final int SCAN_DELETE = 595;
    private static final int SCAN_S1 = 516;
    private static final int SCAN_S2 = 518;
    private static final int SCAN_DIAL1_CW = 525;
    private static final int SCAN_DIAL1_CCW = 526;

    private static final String[] STEP_NAMES = new String[] {
        "SAT_0",
        "SAT_M3",
        "SAT_P3",
        "MTX_IDENTITY",
        "MTX_G2R_64",
        "MTX_R2B_64",
        "SC_OFF",
        "SC_CH0_RED_BASE",
        "SC_CH0_SAT_0",
        "SC_CH0_SAT_63",
        "SC_CH0_PHASE_M30",
        "SC_CH0_PHASE_P30",
        "SC_CH0_RANGE_1",
        "SC_CH0_RANGE_63",
        "SC_CH1_BLUE_SAT_63"
    };

    private static final int[] MTX_ID = {
        1024,0,0, 0,1024,0, 0,0,1024
    };
    private static final int[] MTX_G2R_64 = {
        960,64,0, 0,1024,0, 0,0,1024
    };
    private static final int[] MTX_R2B_64 = {
        1024,0,0, 0,1024,0, 64,0,960
    };

    private SurfaceHolder holder;
    private TextView overlay;
    private Object cameraEx;
    private Camera normal;
    private boolean previewStarted;
    private boolean taking;
    private boolean applied;
    private int step;

    private int originalSaturation;
    private int satMin = -3;
    private int satMax = 3;
    private int[] originalMatrix;
    private String originalColorSelectMode = "off";
    private int maxColorSelectChannels;
    private Sel[] originalSelected;
    private boolean backupReady;

    private File logFile;
    private File photoDir;

    private static class Sel {
        int y, cb, cr, phase, range, saturation;
        Sel copy() {
            Sel s = new Sel();
            s.y=y; s.cb=cb; s.cr=cr; s.phase=phase; s.range=range; s.saturation=saturation;
            return s;
        }
        public String toString() {
            return "Y="+y+",Cb="+cb+",Cr="+cr+",Phase="+phase+",Range="+range+",Saturation="+saturation;
        }
    }

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
        overlay.setBackgroundColor(0x99000000);
        overlay.setTextSize(16);
        overlay.setGravity(Gravity.LEFT | Gravity.TOP);
        overlay.setPadding(12, 8, 12, 8);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2);
        lp.gravity = Gravity.TOP;
        root.addView(overlay, lp);
        setContentView(root);

        File lutDir = new File(Environment.getExternalStorageDirectory(), "LUTS");
        if (!lutDir.exists()) lutDir.mkdirs();
        logFile = new File(lutDir, "COLORTEST.LOG");
        photoDir = new File(new File(Environment.getExternalStorageDirectory(), "DCIM"), "COLORTEST");
        if (!photoDir.exists()) photoDir.mkdirs();
        appendLog("\n=== START "+now()+" ===");
        refresh("Opening CameraEx...");
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
            backupOriginals();
            startPreviewIfReady();
            refresh("READY - CENTER applies step");
        } catch (Throwable t) {
            Log.e(TAG, "open failed", t);
            appendLog("OPEN ERROR "+stack(t));
            refresh("OPEN ERROR: "+t);
        }
    }

    private Object modifier(Camera.Parameters p) throws Exception {
        return call(cameraEx, "createParametersModifier",
                new Class[]{Camera.Parameters.class}, new Object[]{p});
    }

    private void backupOriginals() {
        try {
            Camera.Parameters p = normal.getParameters();
            Object mod = modifier(p);
            originalSaturation = intCall(mod, "getSaturation", 0);
            satMin = intCall(mod, "getMinSaturation", -3);
            satMax = intCall(mod, "getMaxSaturation", 3);
            originalMatrix = (int[]) call(mod, "getRGBMatrix", new Class[0], new Object[0]);
            if (originalMatrix != null) originalMatrix = originalMatrix.clone();
            Object m = safeCall(mod, "getColorSelectMode");
            if (m != null) originalColorSelectMode = String.valueOf(m);
            maxColorSelectChannels = intCall(mod, "getMaxColorSelectChannels", 0);
            int n = Math.max(0, Math.min(maxColorSelectChannels, 8));
            originalSelected = new Sel[n];
            for (int i=0;i<n;i++) {
                originalSelected[i] = readSelected(i);
            }
            backupReady = true;
            appendLog("BACKUP saturation="+originalSaturation+" min="+satMin+" max="+satMax);
            appendLog("BACKUP matrix="+arr(originalMatrix));
            appendLog("BACKUP colorSelectMode="+originalColorSelectMode+" maxChannels="+maxColorSelectChannels);
            for (int i=0;i<n;i++) appendLog("BACKUP channel"+i+"="+originalSelected[i]);
        } catch (Throwable t) {
            appendLog("BACKUP ERROR "+stack(t));
            refresh("BACKUP ERROR: "+t);
        }
    }

    private void isolateBase() throws Exception {
        if (!backupReady) return;
        Camera.Parameters p = normal.getParameters();
        Object mod = modifier(p);
        call(mod, "setSaturation", new Class[]{Integer.TYPE},
                new Object[]{Integer.valueOf(originalSaturation)});
        if (originalMatrix != null && originalMatrix.length==9) {
            call(mod, "setRGBMatrix", new Class[]{int[].class},
                    new Object[]{originalMatrix.clone()});
        }
        call(mod, "setColorSelectMode", new Class[]{String.class, int[].class},
                new Object[]{"off", new int[0]});
        normal.setParameters(p);
    }

    private void restoreOriginals() {
        if (!backupReady || normal == null) return;
        try {
            Camera.Parameters p = normal.getParameters();
            Object mod = modifier(p);
            call(mod, "setSaturation", new Class[]{Integer.TYPE},
                    new Object[]{Integer.valueOf(originalSaturation)});
            if (originalMatrix != null && originalMatrix.length==9) {
                call(mod, "setRGBMatrix", new Class[]{int[].class},
                        new Object[]{originalMatrix.clone()});
            }

            if ("off".equals(originalColorSelectMode)) {
                call(mod, "setColorSelectMode", new Class[]{String.class,int[].class},
                        new Object[]{"off",new int[0]});
            } else {
                int n = originalSelected == null ? 0 : originalSelected.length;
                int[] enabled = new int[n];
                for (int i=0;i<n;i++) enabled[i]=i;
                call(mod, "setColorSelectMode", new Class[]{String.class,int[].class},
                        new Object[]{originalColorSelectMode,enabled});
            }
            normal.setParameters(p);
            if (originalSelected != null && !"off".equals(originalColorSelectMode)) {
                for (int i=0;i<originalSelected.length;i++) {
                    if (originalSelected[i] != null) writeSelected(i, originalSelected[i]);
                }
            }
            appendLog("RESTORE OK saturation="+readSaturation()+" matrix="+arr(readMatrix())
                    +" mode="+readColorSelectMode());
            applied = false;
            refresh("RESTORED original parameters");
        } catch (Throwable t) {
            appendLog("RESTORE ERROR "+stack(t));
            refresh("RESTORE ERROR: "+t);
        }
    }

    private void applyStep() {
        if (normal == null || cameraEx == null || taking) return;
        try {
            isolateBase();
            int idx = step;
            if (idx <= 2) {
                int v = idx==0 ? 0 : (idx==1 ? -3 : 3);
                v = Math.max(satMin, Math.min(satMax, v));
                setSaturation(v);
            } else if (idx == 3) {
                setMatrix(MTX_ID);
            } else if (idx == 4) {
                setMatrix(MTX_G2R_64);
            } else if (idx == 5) {
                setMatrix(MTX_R2B_64);
            } else if (idx == 6) {
                setColorSelectOff();
            } else {
                if (maxColorSelectChannels < 1) throw new IllegalStateException(
                        "getMaxColorSelectChannels="+maxColorSelectChannels);
                if (idx == 14 && maxColorSelectChannels < 2) throw new IllegalStateException(
                        "channel 1 unavailable, maxChannels="+maxColorSelectChannels);

                Sel s;
                int ch;
                if (idx == 14) {
                    ch = 1;
                    s = blueRef();
                    s.saturation = 63;
                } else {
                    ch = 0;
                    s = redRef();
                    if (idx == 8) s.saturation = 0;
                    if (idx == 9) s.saturation = 63;
                    if (idx == 10) s.phase = mod360(s.phase - 30);
                    if (idx == 11) s.phase = mod360(s.phase + 30);
                    if (idx == 12) s.range = 1;
                    if (idx == 13) s.range = 63;
                }
                setSelectedRevision(ch, s);
            }
            applied = true;
            String rb = readback();
            appendLog("STEP "+two(step+1)+" "+STEP_NAMES[step]+" APPLY OK "+rb);
            refresh("APPLY OK\n"+rb);
        } catch (Throwable t) {
            applied = false;
            appendLog("STEP "+two(step+1)+" "+STEP_NAMES[step]+" APPLY ERROR "+stack(t));
            refresh("APPLY ERROR: "+t);
        }
    }

    private void setSaturation(int v) throws Exception {
        Camera.Parameters p=normal.getParameters();
        Object mod=modifier(p);
        call(mod,"setSaturation",new Class[]{Integer.TYPE},new Object[]{Integer.valueOf(v)});
        normal.setParameters(p);
    }

    private int readSaturation() {
        try { return intCall(modifier(normal.getParameters()),"getSaturation",999); }
        catch(Throwable t) { return 999; }
    }

    private void setMatrix(int[] m) throws Exception {
        Camera.Parameters p=normal.getParameters();
        Object mod=modifier(p);
        call(mod,"setRGBMatrix",new Class[]{int[].class},new Object[]{m.clone()});
        normal.setParameters(p);
    }

    private int[] readMatrix() {
        try { return (int[])call(modifier(normal.getParameters()),"getRGBMatrix",new Class[0],new Object[0]); }
        catch(Throwable t) { return null; }
    }

    private void setColorSelectOff() throws Exception {
        Camera.Parameters p=normal.getParameters();
        Object mod=modifier(p);
        call(mod,"setColorSelectMode",new Class[]{String.class,int[].class},
                new Object[]{"off",new int[0]});
        normal.setParameters(p);
    }

    private void setSelectedRevision(int ch, Sel s) throws Exception {
        Camera.Parameters p=normal.getParameters();
        Object mod=modifier(p);
        call(mod,"setColorSelectMode",new Class[]{String.class,int[].class},
                new Object[]{"revision",new int[]{ch}});
        normal.setParameters(p);
        writeSelected(ch,s);
    }

    private String readColorSelectMode() {
        try {
            Object x=call(modifier(normal.getParameters()),"getColorSelectMode",new Class[0],new Object[0]);
            return String.valueOf(x);
        } catch(Throwable t) { return "?"; }
    }

    private Sel readSelected(int ch) {
        try {
            Object o=call(cameraEx,"getChannelColorSelect",new Class[]{Integer.TYPE},
                    new Object[]{Integer.valueOf(ch)});
            if (o==null) return null;
            Sel s=new Sel();
            s.y=getFieldInt(o,"Y"); s.cb=getFieldInt(o,"Cb"); s.cr=getFieldInt(o,"Cr");
            s.phase=getFieldInt(o,"Phase"); s.range=getFieldInt(o,"Range");
            s.saturation=getFieldInt(o,"Saturation");
            return s;
        } catch(Throwable t) {
            appendLog("readSelected ch="+ch+" ERROR "+t);
            return null;
        }
    }

    private void writeSelected(int ch, Sel s) throws Exception {
        Class<?> c=Class.forName("com.sony.scalar.hardware.CameraEx$SelectedColor");
        Constructor<?> ctor=c.getConstructor(new Class[0]);
        Object o=ctor.newInstance(new Object[0]);
        setFieldInt(o,"Y",s.y); setFieldInt(o,"Cb",s.cb); setFieldInt(o,"Cr",s.cr);
        setFieldInt(o,"Phase",s.phase); setFieldInt(o,"Range",s.range);
        setFieldInt(o,"Saturation",s.saturation);
        call(cameraEx,"setColorSelectToChannel",new Class[]{Integer.TYPE,c},
                new Object[]{Integer.valueOf(ch),o});
    }

    private Sel redRef() {
        Sel s=new Sel(); s.phase=90; s.range=33; s.saturation=25; return s;
    }
    private Sel blueRef() {
        Sel s=new Sel(); s.phase=330; s.range=30; s.saturation=14; return s;
    }

    private String readback() {
        StringBuilder sb=new StringBuilder();
        sb.append("Sat=").append(readSaturation());
        sb.append(" Matrix=").append(arr(readMatrix()));
        sb.append(" SCmode=").append(readColorSelectMode());
        if (step>=7) {
            int ch=step==14?1:0;
            sb.append(" Ch").append(ch).append("=").append(readSelected(ch));
        }
        return sb.toString();
    }

    private void capture() {
        if (normal==null || taking) return;
        if (!applied) {
            refresh("APPLY FIRST: press CENTER");
            return;
        }
        taking=true;
        final int captureStep=step;
        final String base=two(captureStep+1)+"_"+STEP_NAMES[captureStep];
        appendLog("CAPTURE START "+base);
        try {
            normal.takePicture(null,null,new Camera.PictureCallback() {
                public void onPictureTaken(byte[] data, Camera camera) {
                    try {
                        File f=uniqueFile(photoDir,base,".JPG");
                        FileOutputStream os=new FileOutputStream(f);
                        os.write(data); os.close();
                        appendLog("CAPTURE OK "+base+" file="+f.getAbsolutePath()+" bytes="+data.length);
                        refresh("CAPTURE OK: "+f.getName());
                    } catch(Throwable t) {
                        appendLog("CAPTURE WRITE ERROR "+stack(t));
                        refresh("CAPTURE ERROR: "+t);
                    }
                    try { camera.startPreview(); previewStarted=true; } catch(Throwable ignored) {}
                    taking=false;
                }
            });
        } catch(Throwable t) {
            taking=false;
            appendLog("CAPTURE ERROR "+stack(t));
            refresh("CAPTURE ERROR: "+t);
        }
    }

    private static File uniqueFile(File dir,String base,String ext) {
        File f=new File(dir,base+ext);
        int n=2;
        while(f.exists()) f=new File(dir,base+"_R"+(n++)+ext);
        return f;
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent e) {
        int scan=e.getScanCode();
        if (scan==SCAN_MENU || keyCode==KeyEvent.KEYCODE_MENU) {
            restoreOriginals(); releaseCamera(); finish(); return true;
        }
        if (scan==SCAN_DELETE || keyCode==KeyEvent.KEYCODE_DEL) {
            restoreOriginals(); return true;
        }
        if (scan==SCAN_S1) {
            try { if(normal!=null) normal.autoFocus(null); } catch(Throwable t) {}
            return true;
        }
        if (scan==SCAN_S2) { capture(); return true; }

        boolean prev=(scan==SCAN_DIAL1_CCW || keyCode==KeyEvent.KEYCODE_DPAD_LEFT);
        boolean next=(scan==SCAN_DIAL1_CW || keyCode==KeyEvent.KEYCODE_DPAD_RIGHT);
        if (prev || next) {
            step=(step+(next?1:STEP_NAMES.length-1))%STEP_NAMES.length;
            applied=false;
            refresh("NOT APPLIED");
            return true;
        }
        if (keyCode==KeyEvent.KEYCODE_DPAD_CENTER || keyCode==KeyEvent.KEYCODE_ENTER) {
            applyStep(); return true;
        }
        return super.onKeyDown(keyCode,e);
    }

    @Override public boolean onKeyUp(int keyCode,KeyEvent e) {
        int s=e.getScanCode();
        if (s==SCAN_MENU || s==SCAN_DELETE || s==SCAN_S1 || s==SCAN_S2 ||
                s==SCAN_DIAL1_CW || s==SCAN_DIAL1_CCW ||
                keyCode==KeyEvent.KEYCODE_DPAD_LEFT || keyCode==KeyEvent.KEYCODE_DPAD_RIGHT ||
                keyCode==KeyEvent.KEYCODE_DPAD_CENTER || keyCode==KeyEvent.KEYCODE_ENTER) return true;
        return super.onKeyUp(keyCode,e);
    }

    private void refresh(String status) {
        if (overlay==null) return;
        String expected=two(step+1)+"_"+STEP_NAMES[step]+".JPG";
        overlay.setText("A6000 COLOR PIPELINE TEST  v0.1\n"
                +"Step "+two(step+1)+"/"+STEP_NAMES.length+"  "+STEP_NAMES[step]+"\n"
                +"Expected: /DCIM/COLORTEST/"+expected+"\n"
                +"Sat range reported: "+satMin+".."+satMax+"   SC channels: "+maxColorSelectChannels+"\n"
                +(applied?"[APPLIED] ":"[NOT APPLIED] ")+status+"\n"
                +"LEFT/RIGHT: step  CENTER: apply  S1: AF  S2: capture\n"
                +"DELETE: restore  MENU: restore + exit");
    }

    @Override public void surfaceCreated(SurfaceHolder h) { startPreviewIfReady(); }
    @Override public void surfaceChanged(SurfaceHolder h,int f,int w,int he) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) { previewStarted=false; }

    private void startPreviewIfReady() {
        if (normal==null || holder==null || previewStarted) return;
        try {
            normal.setPreviewDisplay(holder);
            normal.startPreview();
            previewStarted=true;
        } catch(Throwable t) {
            appendLog("PREVIEW ERROR "+stack(t));
        }
    }

    private void releaseCamera() {
        try { if(normal!=null && previewStarted) normal.stopPreview(); } catch(Throwable ignored) {}
        try { if(cameraEx!=null) call(cameraEx,"release",new Class[0],new Object[0]); } catch(Throwable ignored) {}
        normal=null; cameraEx=null; previewStarted=false;
    }

    @Override protected void onPause() {
        super.onPause();
        if (isFinishing()) {
            restoreOriginals();
            releaseCamera();
            appendLog("=== END "+now()+" ===");
        }
    }

    private static Object call(Object o,String n,Class[] types,Object[] args) throws Exception {
        Method m=o.getClass().getMethod(n,types);
        return m.invoke(o,args);
    }
    private static Object safeCall(Object o,String n) {
        try { return call(o,n,new Class[0],new Object[0]); } catch(Throwable t) { return null; }
    }
    private static int intCall(Object o,String n,int def) {
        try { return ((Integer)call(o,n,new Class[0],new Object[0])).intValue(); }
        catch(Throwable t) { return def; }
    }
    private static int getFieldInt(Object o,String n) throws Exception {
        Field f=o.getClass().getField(n); return f.getInt(o);
    }
    private static void setFieldInt(Object o,String n,int v) throws Exception {
        Field f=o.getClass().getField(n); f.setInt(o,v);
    }
    private static int mod360(int x) { x%=360; return x<0?x+360:x; }
    private static String arr(int[] a) {
        if(a==null) return "null";
        StringBuilder s=new StringBuilder("[");
        for(int i=0;i<a.length;i++){ if(i>0)s.append(','); s.append(a[i]); }
        return s.append(']').toString();
    }
    private void appendLog(String s) {
        Log.i(TAG,s);
        try {
            FileOutputStream os=new FileOutputStream(logFile,true);
            os.write((s+"\n").getBytes("UTF-8")); os.close();
        } catch(Throwable ignored) {}
    }
    private static String stack(Throwable t) {
        Throwable c=t;
        while(c.getCause()!=null) c=c.getCause();
        return t.getClass().getSimpleName()+": "+String.valueOf(c);
    }
    private static String two(int x) { return x<10?"0"+x:String.valueOf(x); }
    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(new Date());
    }
}
