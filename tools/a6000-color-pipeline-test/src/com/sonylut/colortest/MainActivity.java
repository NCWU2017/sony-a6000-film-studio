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
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationHandler;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;

/**
 * Sony A6000 color-pipeline calibration probe — automatic sequence.
 *
 * Automatic flow:
 * open camera -> start preview -> autofocus once -> for each test:
 * reset common parameters -> write this test parameter -> readback ->
 * wait for ISP settle -> capture -> restart preview -> next test.
 *
 * Output:
 * Sony native DSCxxxxx.JPG files are left untouched.
 * /LUTS/COLORTEST.LOG and /LUTS/COLORTEST_MAP.csv are best-effort logs.
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "A6000ColorTest";
    private static final int SCAN_MENU = 514;
    private static final int SCAN_DELETE = 595;

    private static final long START_DELAY_MS = 1200;
    private static final long AF_TIMEOUT_MS = 3500;
    private static final long AFTER_AF_DELAY_MS = 500;
    private static final long ISP_SETTLE_MS = 900;
    private static final long BETWEEN_SHOTS_MS = 1100;

    private static final String[] STEP_NAMES = new String[] {
        "REV_RGAIN_0_BASE",
        "REV_RGAIN_P4",
        "REV_RGAIN_0_RESTORE"
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

    private final Handler handler = new Handler();

    private SurfaceHolder holder;
    private TextView overlay;
    private Object cameraEx;
    private Camera normal;
    private boolean previewStarted;
    private boolean taking;
    private boolean autoStarted;
    private boolean autoRunning;
    private boolean sequenceDone;
    private boolean afFinished;
    private int telnetPort = -1;
    private int scalarPid = -1;
    private long scalarBias = 0;
    private long patchTarget = 0;
    private boolean patchVerified = false;
    private boolean patchApplied = false;
    private static final long PATCH_VMA = 0x000E2C86L;
    private static final String BYTES_ORIG = "3b 6c";
    private static final String BYTES_P4 = "04 23";
    private int step;
    private Object shutterProxy;
    private Set<String> beforeCapturePaths = new HashSet<String>();
    private File pendingNativeFile;
    private long pendingNativeSize = -1;
    private int pendingStableCount;
    private int nativePollCount;

    private int originalSaturation;
    private int satMin = -3;
    private int satMax = 3;
    private int[] originalMatrix;
    private String originalColorSelectMode = "off";
    private int maxColorSelectChannels;
    private Sel[] originalSelected;
    private boolean backupReady;

    private File logFile;
    private File mapFile;
    private File photoRoot;
    private String sessionName;

    private static class Sel {
        int y, cb, cr, phase, range, saturation;
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
        mapFile = new File(lutDir, "COLORTEST_MAP.csv");
        if (!mapFile.exists()) {
            appendMap("session,step,test_name,native_filename,native_path,bytes");
        }

        photoRoot = new File(Environment.getExternalStorageDirectory(), "DCIM");
        sessionName = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());

        appendLog("\n=== AUTO START "+now()+" session="+sessionName+" ===");
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
            installShutterListener();
            backupOriginals();
            startPreviewIfReady();
            maybeStartAuto();
        } catch (Throwable t) {
            failStop("OPEN ERROR", t);
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
            for (int i=0;i<n;i++) originalSelected[i] = readSelected(i);
            backupReady = true;

            appendLog("BACKUP saturation="+originalSaturation+" min="+satMin+" max="+satMax);
            appendLog("BACKUP matrix="+arr(originalMatrix));
            appendLog("BACKUP colorSelectMode="+originalColorSelectMode+" maxChannels="+maxColorSelectChannels);
            for (int i=0;i<n;i++) appendLog("BACKUP channel"+i+"="+originalSelected[i]);
        } catch (Throwable t) {
            failStop("BACKUP ERROR", t);
        }
    }

    private void maybeStartAuto() {
        if (autoStarted || !backupReady || !previewStarted || normal == null) return;
        autoStarted = true;
        autoRunning = true;
        refresh("Preview ready. Autofocus will start...");
        handler.postDelayed(new Runnable() {
            public void run() { startInitialAutofocus(); }
        }, START_DELAY_MS);
    }

    private void startInitialAutofocus() {
        if (!autoRunning || normal == null) return;
        afFinished = false;
        refresh("AUTOFOCUS...");
        appendLog("AF START");
        try {
            normal.autoFocus(new Camera.AutoFocusCallback() {
                public void onAutoFocus(boolean success, Camera camera) {
                    if (afFinished || !autoRunning) return;
                    afFinished = true;
                    appendLog("AF CALLBACK success="+success);
                    refresh("AF "+(success ? "LOCK" : "DONE/UNCONFIRMED")+" -> start tests");
                    handler.postDelayed(new Runnable() {
                        public void run() { runCurrentStep(); }
                    }, AFTER_AF_DELAY_MS);
                }
            });
        } catch (Throwable t) {
            appendLog("AF ERROR "+stack(t)+"; continue after timeout path");
        }

        handler.postDelayed(new Runnable() {
            public void run() {
                if (!autoRunning || afFinished) return;
                afFinished = true;
                appendLog("AF TIMEOUT after "+AF_TIMEOUT_MS+"ms; continue");
                refresh("AF TIMEOUT -> continue tests");
                handler.postDelayed(new Runnable() {
                    public void run() { runCurrentStep(); }
                }, AFTER_AF_DELAY_MS);
            }
        }, AF_TIMEOUT_MS);
    }

    private void runCurrentStep() {
        if (!autoRunning || taking || normal == null) return;
        if (step == 0 && !patchVerified) {
            appendLog("VERIFY GATE step0 patchVerified=false -> verifyPatchTargetThenStart");
            refresh("VERIFY STATE=START\nChecking ScalarDaemon target before BASE...");
            verifyPatchTargetThenStart();
            return;
        }
        try {
            appendLog("STEP "+two(step+1)+" "+STEP_NAMES[step]+" RESET START");
            isolateBase();
            appendLog("STEP "+two(step+1)+" "+STEP_NAMES[step]+" RESET OK");

            applyStepOnly(step);
            String rb = readback(step);
            appendLog("STEP "+two(step+1)+" "+STEP_NAMES[step]+" WRITE/READBACK OK "+rb);
            refresh("RESET OK -> WRITE OK -> waiting ISP\n"+rb);

            handler.postDelayed(new Runnable() {
                public void run() { captureCurrentStep(); }
            }, ISP_SETTLE_MS);
        } catch (Throwable t) {
            failStop("STEP "+two(step+1)+" "+STEP_NAMES[step]+" APPLY ERROR", t);
        }
    }

    private void applyStepOnly(int idx) throws Exception {
        if (maxColorSelectChannels < 1)
            throw new IllegalStateException("getMaxColorSelectChannels="+maxColorSelectChannels);

        Sel color = redRef();
        setSelected(0, "revision", color);

        String mode = readColorSelectMode();
        Sel rb = readSelected(0);
        appendLog("STEP "+two(idx+1)+" "+STEP_NAMES[idx]
                +" mode="+mode+" ch0="+rb);
        if (!"revision".equals(mode))
            throw new IllegalStateException("revision did not stick, readback="+mode);
    }

    private void isolateBase() throws Exception {
        if (!backupReady) throw new IllegalStateException("backup not ready");
        Camera.Parameters p = normal.getParameters();
        Object mod = modifier(p);

        call(mod, "setSaturation", new Class[]{Integer.TYPE},
                new Object[]{Integer.valueOf(Math.max(satMin, Math.min(satMax, 0)))});

        call(mod, "setRGBMatrix", new Class[]{int[].class},
                new Object[]{MTX_ID.clone()});

        call(mod, "setColorSelectMode", new Class[]{String.class, int[].class},
                new Object[]{"off", new int[0]});
        normal.setParameters(p);
    }

    private void captureCurrentStep() {
        if (!autoRunning || cameraEx == null || taking) return;

        appendLog("STEP "+two(step+1)+" "+STEP_NAMES[step]
                +" PRECAPTURE READBACK "+readback(step));
        taking = true;
        final int captureStep = step;
        final String base = two(captureStep+1)+"_"+STEP_NAMES[captureStep];
        beforeCapturePaths = collectJpegPaths(photoRoot);
        pendingNativeFile = null;
        pendingNativeSize = -1;
        pendingStableCount = 0;
        nativePollCount = 0;
        appendLog("CAPTURE START "+base+" mode=CameraEx.burstableTakePicture");

        try {
            call(cameraEx, "burstableTakePicture", new Class[0], new Object[0]);
            refresh("NATIVE CAPTURE TRIGGERED: "+base+"\nWaiting for Sony JPEG...");
        } catch (Throwable t) {
            taking = false;
            failStop("NATIVE CAPTURE ERROR "+base, t);
        }
    }

    private void installShutterListener() throws Exception {
        final Class<?> iface = Class.forName("com.sony.scalar.hardware.CameraEx$ShutterListener");
        shutterProxy = Proxy.newProxyInstance(iface.getClassLoader(), new Class[]{iface},
                new InvocationHandler() {
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("onShutter".equals(method.getName())) {
                            appendLog("SHUTTER CALLBACK");
                            try {
                                call(cameraEx, "cancelTakePicture", new Class[0], new Object[0]);
                            } catch (Throwable t) {
                                appendLog("cancelTakePicture ERROR "+stack(t));
                            }
                            handler.postDelayed(new Runnable() {
                                public void run() { pollNativeJpeg(); }
                            }, 700);
                        }
                        return null;
                    }
                });
        call(cameraEx, "setShutterListener", new Class[]{iface}, new Object[]{shutterProxy});
        appendLog("SHUTTER LISTENER OK");
    }

    private void pollNativeJpeg() {
        if (!autoRunning || !taking) return;
        nativePollCount++;
        File f = findNewJpeg(photoRoot, beforeCapturePaths);
        if (f != null) {
            if (pendingNativeFile == null || !f.getAbsolutePath().equals(pendingNativeFile.getAbsolutePath())) {
                pendingNativeFile = f;
                pendingNativeSize = f.length();
                pendingStableCount = 0;
                appendLog("NEW JPEG FOUND "+f.getAbsolutePath()+" size="+pendingNativeSize);
            } else {
                long len = f.length();
                if (len > 0 && len == pendingNativeSize) {
                    pendingStableCount++;
                } else {
                    pendingStableCount = 0;
                    pendingNativeSize = len;
                }
                if (pendingStableCount >= 2) {
                    finishNativeCapture(f);
                    return;
                }
            }
        }
        if (nativePollCount >= 20) {
            taking = false;
            failStop("CAPTURE FAILED "+two(step+1)+"_"+STEP_NAMES[step],
                    new RuntimeException("Sony native JPEG not found/stable"));
            return;
        }
        handler.postDelayed(new Runnable() {
            public void run() { pollNativeJpeg(); }
        }, 500);
    }

    private void finishNativeCapture(File src) {
        final int captureStep = step;
        final String stepNo = two(captureStep+1);
        final String testName = STEP_NAMES[captureStep];

        // Do not rename, move, copy or modify Sony's native JPEG.
        // Record only the step <-> native filename/path relationship.
        appendLog("STEP "+stepNo+" "+testName
                +" POSTCAPTURE READBACK "+readback(captureStep));
        appendLog("MAP "+stepNo+" "+testName+" -> "
                +src.getAbsolutePath()+" bytes="+src.length());
        appendMap(csv(sessionName)+","+stepNo+","+csv(testName)+","
                +csv(src.getName())+","+csv(src.getAbsolutePath())+","+src.length());

        refresh("CAPTURE OK\n"+stepNo+" "+testName+" -> "+src.getName()
                +"\nMapping logged; native photo untouched.");

        taking = false;
        if (captureStep == 0) {
            patchPlus4ThenNext();
        } else if (captureStep == 1) {
            restorePatchThenNext();
        } else {
            finishSequence();
        }
    }


    private void verifyPatchTargetThenStart() {
        refresh("Verifying ScalarDaemon target...");
        new Thread(new Runnable() {
            public void run() {
                try {
                    discoverPatchTarget();
                    String rb=readPatchBytes();
                    if(!BYTES_ORIG.equals(rb))
                        throw new IllegalStateException("target bytes="+rb+" expected="+BYTES_ORIG);
                    patchVerified=true;
                    appendLog("PATCH TARGET PASS pid="+scalarPid
                            +" bias=0x"+Long.toHexString(scalarBias)
                            +" target=0x"+Long.toHexString(patchTarget)
                            +" bytes="+rb+" port="+telnetPort);
                    handler.post(new Runnable() {
                        public void run() { step=0; runCurrentStep(); }
                    });
                } catch(final Throwable t) {
                    handler.post(new Runnable() {
                        public void run() { failStop("PATCH VERIFY ERROR",t); }
                    });
                }
            }
        }).start();
    }

    private void patchPlus4ThenNext() {
        refresh("01 BASE captured. Applying R_GAIN=+4...");
        new Thread(new Runnable() {
            public void run() {
                try {
                    if(!patchVerified) throw new IllegalStateException("patch target not verified");
                    writePatch(true);
                    String rb=readPatchBytes();
                    if(!BYTES_P4.equals(rb))
                        throw new IllegalStateException("patch readback="+rb+" expected="+BYTES_P4);
                    patchApplied=true;
                    appendLog("PATCH +4 PASS target=0x"+Long.toHexString(patchTarget));
                    handler.post(new Runnable() {
                        public void run() {
                            step=1;
                            handler.postDelayed(new Runnable() {
                                public void run() { runCurrentStep(); }
                            }, BETWEEN_SHOTS_MS);
                        }
                    });
                } catch(final Throwable t) {
                    handler.post(new Runnable() {
                        public void run() { failStop("PATCH +4 ERROR",t); }
                    });
                }
            }
        }).start();
    }

    private void restorePatchThenNext() {
        refresh("02 +4 captured. Restoring original instruction...");
        new Thread(new Runnable() {
            public void run() {
                try {
                    writePatch(false);
                    String rb=readPatchBytes();
                    if(!BYTES_ORIG.equals(rb))
                        throw new IllegalStateException("restore readback="+rb+" expected="+BYTES_ORIG);
                    patchApplied=false;
                    appendLog("PATCH RESTORE PASS");
                    handler.post(new Runnable() {
                        public void run() {
                            step=2;
                            handler.postDelayed(new Runnable() {
                                public void run() { runCurrentStep(); }
                            }, BETWEEN_SHOTS_MS);
                        }
                    });
                } catch(final Throwable t) {
                    handler.post(new Runnable() {
                        public void run() { failStop("PATCH RESTORE ERROR",t); }
                    });
                }
            }
        }).start();
    }

    private void discoverPatchTarget() throws Exception {
        String scan=execRoot("for f in /proc/[0-9]*/maps; do "
                +"grep -q libScalarDaemon.so $f 2>/dev/null || continue; "
                +"x=${f#/proc/}; x=${x%/maps}; echo PID=$x; cat $f | grep libScalarDaemon.so; break; done");
        scalarPid=parsePidMarker(scan);
        if(scalarPid<=0)
            throw new IllegalStateException("ScalarDaemon owner PID not found: "+compact(scan));
        String maps=execRoot("cat /proc/"+scalarPid+"/maps | grep libScalarDaemon.so");
        scalarBias=parseBias(maps);
        if(scalarBias<=0)
            throw new IllegalStateException("libScalarDaemon map not found: "+compact(maps));
        patchTarget=scalarBias+PATCH_VMA;
        appendLog("DISCOVER pid="+scalarPid+" bias=0x"+Long.toHexString(scalarBias)
                +" target=0x"+Long.toHexString(patchTarget));
    }

    private int parsePidMarker(String s) {
        int p=s.indexOf("PID=");
        if(p>=0) {
            int i=p+4,j=i;
            while(j<s.length() && Character.isDigit(s.charAt(j))) j++;
            try { return Integer.parseInt(s.substring(i,j)); } catch(Throwable ignored) {}
        }
        return -1;
    }

    private long parseBias(String maps) {
        String[] lines=maps.split("\\n");
        for(int k=0;k<lines.length;k++) {
            String line=lines[k].trim();
            if(line.indexOf("libScalarDaemon.so")<0) continue;
            String[] p=line.split("\\s+");
            if(p.length<3) continue;
            int dash=p[0].indexOf('-');
            if(dash<1) continue;
            try {
                long start=Long.parseLong(p[0].substring(0,dash),16);
                long off=Long.parseLong(p[2],16);
                return start-off;
            } catch(Throwable ignored) {}
        }
        return 0;
    }

    private String readPatchBytes() throws Exception {
        String out=execRoot("dd if=/proc/"+scalarPid+"/mem bs=1 skip="+patchTarget
                +" count=2 2>/dev/null | od -An -tx1");
        String z=out.toLowerCase(Locale.US);
        if(z.indexOf("3b 6c")>=0) return BYTES_ORIG;
        if(z.indexOf("04 23")>=0) return BYTES_P4;
        return compact(out);
    }

    private void writePatch(boolean enable) throws Exception {
        String oct=enable ? "\\004\\043" : "\\073\\154";
        execRoot("printf '"+oct+"' | dd of=/proc/"+scalarPid+"/mem bs=1 seek="+patchTarget
                +" count=2 conv=notrunc 2>/dev/null");
    }

    private String execRoot(String command) throws Exception {
        int[] ports=telnetPort>0 ? new int[]{telnetPort} : new int[]{23,2323};
        Throwable last=null;
        for(int pi=0;pi<ports.length;pi++) {
            Socket sock=null;
            try {
                sock=new Socket();
                sock.connect(new InetSocketAddress("127.0.0.1",ports[pi]),1500);
                sock.setSoTimeout(400);
                InputStream in=sock.getInputStream();
                OutputStream out=sock.getOutputStream();
                drainTelnet(in,out,250);
                String marker="__END_"+System.currentTimeMillis()+"__";
                out.write((command+"; echo "+marker+"\r\n").getBytes("US-ASCII"));
                out.flush();
                ByteArrayOutputStream buf=new ByteArrayOutputStream();
                long deadline=System.currentTimeMillis()+6000;
                while(System.currentTimeMillis()<deadline) {
                    try {
                        int b=readTelnetByte(in,out);
                        if(b<0) break;
                        buf.write(b);
                        String cur=new String(buf.toByteArray(),"ISO-8859-1");
                        int m=cur.indexOf(marker);
                        if(m>=0) {
                            telnetPort=ports[pi];
                            try{sock.close();}catch(Throwable ignored){}
                            return cur.substring(0,m);
                        }
                    } catch(SocketTimeoutException timeout) {}
                }
                throw new RuntimeException("root command timeout port="+ports[pi]
                        +" out="+compact(new String(buf.toByteArray(),"ISO-8859-1")));
            } catch(Throwable t) {
                last=t;
                if(sock!=null) try{sock.close();}catch(Throwable ignored){}
            }
        }
        throw new RuntimeException("root Telnet unavailable localhost:23/2323",last);
    }

    private static void drainTelnet(InputStream in,OutputStream out,long ms) throws Exception {
        long end=System.currentTimeMillis()+ms;
        while(System.currentTimeMillis()<end) {
            try { readTelnetByte(in,out); }
            catch(SocketTimeoutException t) { break; }
        }
    }

    private static int readTelnetByte(InputStream in,OutputStream out) throws Exception {
        int b=in.read();
        if(b!=255) return b;
        int cmd=in.read();
        if(cmd<0) return -1;
        if(cmd==255) return 255;
        if(cmd==250) {
            int prev=-1;
            while(true) {
                int x=in.read();
                if(x<0) return -1;
                if(prev==255 && x==240) break;
                prev=x;
            }
            return readTelnetByte(in,out);
        }
        int opt=in.read();
        if(opt<0) return -1;
        if(cmd==251 || cmd==252) out.write(new byte[]{(byte)255,(byte)254,(byte)opt});
        else if(cmd==253 || cmd==254) out.write(new byte[]{(byte)255,(byte)252,(byte)opt});
        out.flush();
        return readTelnetByte(in,out);
    }

    private void restorePatchFailsafe(final String why) {
        if(!patchApplied || patchTarget==0) return;
        new Thread(new Runnable() {
            public void run() {
                try {
                    writePatch(false);
                    String rb=readPatchBytes();
                    appendLog("FAILSAFE "+why+" restore="+rb);
                    if(BYTES_ORIG.equals(rb)) patchApplied=false;
                } catch(Throwable t) {
                    appendLog("FAILSAFE "+why+" ERROR "+stack(t)+" ; power-cycle camera to restore");
                }
            }
        }).start();
    }

    private static String compact(String s) {
        if(s==null) return "";
        s=s.replace('\r',' ').replace('\n',' ').trim();
        while(s.indexOf("  ")>=0) s=s.replace("  "," ");
        return s.length()>180 ? s.substring(0,180) : s;
    }

    private static Set<String> collectJpegPaths(File root) {
        Set<String> out = new HashSet<String>();
        collectJpegPathsRec(root, out, 0);
        return out;
    }

    private static void collectJpegPathsRec(File f, Set<String> out, int depth) {
        if (f == null || depth > 4) return;
        File[] a = f.listFiles();
        if (a == null) return;
        for (int i=0;i<a.length;i++) {
            File x=a[i];
            if (x.isDirectory()) {
                if (!"COLORTEST".equalsIgnoreCase(x.getName()))
                    collectJpegPathsRec(x,out,depth+1);
            } else {
                String n=x.getName().toUpperCase(Locale.US);
                if (n.endsWith(".JPG") || n.endsWith(".JPEG"))
                    out.add(x.getAbsolutePath());
            }
        }
    }

    private static File findNewJpeg(File root, Set<String> before) {
        return findNewJpegRec(root,before,0,null);
    }

    private static File findNewJpegRec(File f, Set<String> before, int depth, File best) {
        if (f == null || depth > 4) return best;
        File[] a=f.listFiles();
        if (a == null) return best;
        for (int i=0;i<a.length;i++) {
            File x=a[i];
            if (x.isDirectory()) {
                if (!"COLORTEST".equalsIgnoreCase(x.getName()))
                    best=findNewJpegRec(x,before,depth+1,best);
            } else {
                String n=x.getName().toUpperCase(Locale.US);
                if ((n.endsWith(".JPG") || n.endsWith(".JPEG"))
                        && !before.contains(x.getAbsolutePath())) {
                    if (best==null || x.lastModified()>best.lastModified()
                            || (x.lastModified()==best.lastModified() && x.length()>best.length()))
                        best=x;
                }
            }
        }
        return best;
    }

    private void finishSequence() {
        autoRunning = false;
        sequenceDone = true;
        appendLog("ALL "+STEP_NAMES.length+" TESTS COMPLETE");
        restoreOriginals();
        appendLog("=== AUTO COMPLETE "+now()+" session="+sessionName+" ===");
        refresh("COMPLETE: 3 photos\n01 R_GAIN=0  02 R_GAIN=+4  03 RESTORED=0\nMapping saved in /LUTS/COLORTEST_MAP.csv\nParameters restored. MENU to exit.");
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
        } catch (Throwable t) {
            appendLog("RESTORE ERROR "+stack(t));
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
        try {
            return (int[])call(modifier(normal.getParameters()),"getRGBMatrix",new Class[0],new Object[0]);
        } catch(Throwable t) { return null; }
    }

    private void setColorSelectOff() throws Exception {
        Camera.Parameters p=normal.getParameters();
        Object mod=modifier(p);
        call(mod,"setColorSelectMode",new Class[]{String.class,int[].class},
                new Object[]{"off",new int[0]});
        normal.setParameters(p);
    }

    private void setSelected(int ch, String mode, Sel s) throws Exception {
        Camera.Parameters p=normal.getParameters();
        Object mod=modifier(p);
        call(mod,"setColorSelectMode",new Class[]{String.class,int[].class},
                new Object[]{mode,new int[]{ch}});
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
        Sel s=new Sel();
        s.phase=90; s.range=33; s.saturation=25;
        s.y=0; s.cb=0; s.cr=0;
        return s;
    }

    private Sel greenRef() {
        Sel s=new Sel();
        s.phase=230; s.range=63; s.saturation=2;
        s.y=0; s.cb=0; s.cr=0;
        return s;
    }

    private Sel blueRef() {
        Sel s=new Sel();
        s.phase=330; s.range=30; s.saturation=14;
        s.y=0; s.cb=0; s.cr=0;
        return s;
    }

    private String readback(int idx) {
        StringBuilder sb=new StringBuilder();
        sb.append("Sat=").append(readSaturation());
        sb.append(" Matrix=").append(arr(readMatrix()));
        sb.append(" SCmode=").append(readColorSelectMode());
        sb.append(" Ch0=").append(readSelected(0));
        return sb.toString();
    }

    private void failStop(String where, Throwable t) {
        autoRunning = false;
        appendLog(where+" "+stack(t));
        restoreOriginals();
        restorePatchFailsafe(where);
        refresh("STOPPED\n"+where+"\n"+stack(t)+"\nParameters restored. MENU to exit.");
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent e) {
        int scan=e.getScanCode();
        if (scan==SCAN_MENU || keyCode==KeyEvent.KEYCODE_MENU) {
            autoRunning=false;
            handler.removeCallbacksAndMessages(null);
            restoreOriginals();
            restorePatchFailsafe("MENU");
            releaseCamera();
            finish();
            return true;
        }
        if (scan==SCAN_DELETE || keyCode==KeyEvent.KEYCODE_DEL) {
            autoRunning=false;
            handler.removeCallbacksAndMessages(null);
            restoreOriginals();
            restorePatchFailsafe("DELETE");
            refresh("AUTO STOPPED manually. Parameters restored. MENU to exit.");
            return true;
        }
        return true; // swallow other keys during automatic calibration
    }

    @Override public boolean onKeyUp(int keyCode,KeyEvent e) {
        return true;
    }

    private void refresh(String status) {
        if (overlay==null) return;
        int shownStep=Math.min(step+1,STEP_NAMES.length);
        String expected=two(shownStep)+" "+STEP_NAMES[Math.min(step,STEP_NAMES.length-1)];
        overlay.setText("A6000 REVISION R_GAIN TEST  v1.5\n"
                +"Session: "+sessionName+"\n"
                +"Step "+two(shownStep)+"/"+STEP_NAMES.length+"  "+STEP_NAMES[Math.min(step,STEP_NAMES.length-1)]+"\n"
                +"Test: "+expected+"\n"
                +"Sat range: "+satMin+".."+satMax+"   SC channels: "+maxColorSelectChannels+"\n"
                +status+"\n"
                +"Flow: verify -> BASE -> auto +4 -> +4 shot -> auto restore -> final shot\n"
                +"DELETE: stop+restore   MENU: restore+exit");
    }

    @Override public void surfaceCreated(SurfaceHolder h) {
        startPreviewIfReady();
        maybeStartAuto();
    }

    @Override public void surfaceChanged(SurfaceHolder h,int f,int w,int he) {}

    @Override public void surfaceDestroyed(SurfaceHolder h) {
        previewStarted=false;
    }

    private void startPreviewIfReady() {
        if (normal==null || holder==null || previewStarted) return;
        try {
            normal.setPreviewDisplay(holder);
            normal.startPreview();
            previewStarted=true;
            appendLog("PREVIEW START OK");
        } catch(Throwable t) {
            failStop("PREVIEW ERROR", t);
        }
    }

    private void releaseCamera() {
        try { if(normal!=null && previewStarted) normal.stopPreview(); } catch(Throwable ignored) {}
        try {
            if(cameraEx!=null) call(cameraEx,"release",new Class[0],new Object[0]);
        } catch(Throwable ignored) {}
        normal=null;
        cameraEx=null;
        previewStarted=false;
    }

    @Override protected void onPause() {
        super.onPause();
        if (isFinishing()) {
            autoRunning=false;
            handler.removeCallbacksAndMessages(null);
            restoreOriginals();
            restorePatchFailsafe("ONPAUSE");
            releaseCamera();
            appendLog("=== END "+now()+" ===");
        }
    }

    private static Object call(Object o,String n,Class[] types,Object[] args) throws Exception {
        Method m=o.getClass().getMethod(n,types);
        return m.invoke(o,args);
    }

    private static Object safeCall(Object o,String n) {
        try { return call(o,n,new Class[0],new Object[0]); }
        catch(Throwable t) { return null; }
    }

    private static int intCall(Object o,String n,int def) {
        try { return ((Integer)call(o,n,new Class[0],new Object[0])).intValue(); }
        catch(Throwable t) { return def; }
    }

    private static int getFieldInt(Object o,String n) throws Exception {
        Field f=o.getClass().getField(n);
        return f.getInt(o);
    }

    private static void setFieldInt(Object o,String n,int v) throws Exception {
        Field f=o.getClass().getField(n);
        f.setInt(o,v);
    }

    private static int mod360(int x) {
        x%=360;
        return x<0?x+360:x;
    }

    private static String arr(int[] a) {
        if(a==null) return "null";
        StringBuilder s=new StringBuilder("[");
        for(int i=0;i<a.length;i++){
            if(i>0)s.append(',');
            s.append(a[i]);
        }
        return s.append(']').toString();
    }

    private void appendLog(String s) {
        Log.i(TAG,s);
        try {
            FileOutputStream os=new FileOutputStream(logFile,true);
            os.write((s+"\n").getBytes("UTF-8"));
            os.close();
        } catch(Throwable ignored) {}
    }

    private void appendMap(String line) {
        try {
            FileOutputStream os=new FileOutputStream(mapFile,true);
            os.write((line+"\n").getBytes("UTF-8"));
            os.close();
        } catch(Throwable t) {
            appendLog("MAP LOG ERROR "+stack(t));
        }
    }

    private static String csv(String v) {
        if (v == null) return "";
        boolean quote = v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0;
        if (v.indexOf('"') >= 0) v = v.replace("\"", "\"\"");
        return quote ? "\""+v+"\"" : v;
    }

    private static String stack(Throwable t) {
        Throwable c=t;
        while(c.getCause()!=null) c=c.getCause();
        return t.getClass().getSimpleName()+": "+String.valueOf(c);
    }

    private static String two(int x) {
        return x<10?"0"+x:String.valueOf(x);
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(new Date());
    }
}
