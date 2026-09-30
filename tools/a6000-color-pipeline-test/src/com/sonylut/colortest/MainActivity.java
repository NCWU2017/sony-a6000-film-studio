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

public class MainActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG="SCRevisionProbe";
    private static final int SCAN_MENU=514, SCAN_DELETE=595;
    private final Handler handler=new Handler();
    private SurfaceHolder holder;
    private TextView overlay;
    private Object cameraEx;
    private Camera normal;
    private boolean previewStarted, started;
    private String originalMode="off";
    private int maxChannels=0;
    private String supported0="?";
    private String offResult="?", extractResult="?", revisionResult="?";
    private String revisionRaw="?";
    private String revisionErr="";
    private String writeStatus="";
    private StringBuilder full=new StringBuilder();

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root=new FrameLayout(this);
        SurfaceView sv=new SurfaceView(this);
        holder=sv.getHolder(); holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS); holder.addCallback(this);
        root.addView(sv,new FrameLayout.LayoutParams(-1,-1));
        overlay=new TextView(this);
        overlay.setTextColor(0xffffffff); overlay.setBackgroundColor(0xdd000000);
        overlay.setTextSize(18); overlay.setGravity(Gravity.LEFT|Gravity.TOP); overlay.setPadding(12,8,12,8);
        root.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
        setContentView(root);
        render("Opening CameraEx...");
        openCamera();
    }

    private void openCamera(){
        try{
            Class<?> cx=Class.forName("com.sony.scalar.hardware.CameraEx");
            Method open=null;
            for(Method m:cx.getMethods()) if("open".equals(m.getName())&&m.getParameterTypes().length==2){open=m;break;}
            if(open==null) throw new NoSuchMethodException("CameraEx.open");
            cameraEx=open.invoke(null,new Object[]{Integer.valueOf(0),null});
            normal=(Camera)call(cameraEx,"getNormalCamera",new Class[0],new Object[0]);
            startPreviewIfReady(); maybeStart();
        }catch(Throwable t){ revisionErr="OPEN "+rootCause(t); finishProbe(); }
    }

    private Object modifier(Camera.Parameters p)throws Exception{
        return call(cameraEx,"createParametersModifier",new Class[]{Camera.Parameters.class},new Object[]{p});
    }

    private void maybeStart(){
        if(started||normal==null||!previewStarted)return;
        started=true;
        handler.postDelayed(new Runnable(){public void run(){runProbe();}},700);
    }

    private void runProbe(){
        try{
            Camera.Parameters p0=normal.getParameters();
            Object m0=modifier(p0);
            Object sup=call(m0,"getSupportedColorSelectModes",new Class[0],new Object[0]);
            supported0=formatAny(sup);
            originalMode=String.valueOf(call(m0,"getColorSelectMode",new Class[0],new Object[0]));
            maxChannels=((Integer)call(m0,"getMaxColorSelectChannels",new Class[0],new Object[0])).intValue();
            log("SUPPORTED="+supported0);
            log("ORIGINAL="+originalMode+" MAX_CH="+maxChannels);

            offResult=testMode("off",new int[0],false);
            extractResult=testMode("extract",maxChannels>0?new int[]{0}:new int[0],false);
            revisionResult=testMode("revision",maxChannels>0?new int[]{0}:new int[0],true);

            restoreOriginal();
        }catch(Throwable t){
            revisionErr="PROBE "+rootCause(t);
            try{restoreOriginal();}catch(Throwable ignored){}
        }
        finishProbe();
    }

    private String testMode(String mode,int[] channels,boolean captureRevision){
        String result="?";
        try{
            Camera.Parameters p=normal.getParameters();
            Object mod=modifier(p);
            call(mod,"setColorSelectMode",new Class[]{String.class,int[].class},new Object[]{mode,channels});
            normal.setParameters(p);
            try{Thread.sleep(250);}catch(InterruptedException ignored){}
            Camera.Parameters after=normal.getParameters();
            Object ma=modifier(after);
            result=String.valueOf(call(ma,"getColorSelectMode",new Class[0],new Object[0]));
            String raw=null;
            try{raw=after.get("color-select-mode");}catch(Throwable ignored){}
            String vals=null;
            try{vals=after.get("color-select-mode-values");}catch(Throwable ignored){}
            log(mode+": readback="+result+" raw="+raw+" values="+vals);
            if(captureRevision) revisionRaw=String.valueOf(raw)+" / values="+String.valueOf(vals);
        }catch(Throwable t){
            String e=rootCause(t);
            log(mode+": ERROR "+e);
            if(captureRevision) revisionErr=e;
            result="ERROR";
        }
        return result;
    }

    private void finishProbe(){
        writeStatus=writeEverywhere();
        render(summary());
    }

    private String summary(){
        StringBuilder s=new StringBuilder();
        s.append("A6000 SC revision probe v0.8\n\n");
        s.append("SUPPORTED: ").append(supported0).append("\n");
        s.append("ORIGINAL: ").append(originalMode).append("\n");
        s.append("MAX CH: ").append(maxChannels).append("\n\n");
        s.append("OFF -> ").append(offResult).append("\n");
        s.append("EXTRACT -> ").append(extractResult).append("\n");
        s.append("REVISION -> ").append(revisionResult).append("\n");
        s.append("REV RAW: ").append(revisionRaw).append("\n");
        if(revisionErr.length()>0) s.append("REV ERR: ").append(revisionErr).append("\n");
        s.append("\n").append(writeStatus).append("\n");
        s.append("MENU: restore+exit");
        return s.toString();
    }

    private String writeEverywhere(){
        File root=Environment.getExternalStorageDirectory();
        String text=full.toString()+"\n--- SUMMARY ---\n"+summaryNoWrite();
        StringBuilder st=new StringBuilder("FILE:");
        File[] fs=new File[]{
            new File(root,"SCREV.TXT"),
            new File(new File(root,"DCIM"),"SCREV.TXT"),
            new File(new File(root,"LUTS"),"SCREV.TXT")
        };
        for(int i=0;i<fs.length;i++){
            try{
                File parent=fs[i].getParentFile(); if(parent!=null&&!parent.exists()) parent.mkdirs();
                FileOutputStream os=new FileOutputStream(fs[i],false);
                os.write(text.getBytes("UTF-8")); os.close();
                st.append(" ").append(fs[i].getAbsolutePath()).append("=OK");
            }catch(Throwable t){ st.append(" ").append(fs[i].getAbsolutePath()).append("=FAIL"); }
        }
        return st.toString();
    }

    private String summaryNoWrite(){
        return "SUPPORTED="+supported0+"\nORIGINAL="+originalMode+"\nMAX_CH="+maxChannels+
                "\nOFF="+offResult+"\nEXTRACT="+extractResult+"\nREVISION="+revisionResult+
                "\nREV_RAW="+revisionRaw+"\nREV_ERR="+revisionErr+"\n";
    }

    private void restoreOriginal()throws Exception{
        if(normal==null)return;
        Camera.Parameters p=normal.getParameters();
        Object mod=modifier(p);
        int[] channels;
        if("off".equals(originalMode)) channels=new int[0];
        else{
            int n=Math.max(0,Math.min(maxChannels,2)); channels=new int[n];
            for(int i=0;i<n;i++) channels[i]=i;
        }
        call(mod,"setColorSelectMode",new Class[]{String.class,int[].class},new Object[]{originalMode,channels});
        normal.setParameters(p);
    }

    private void log(String s){ full.append(s).append('\n'); Log.i(TAG,s); }

    private static String formatAny(Object o){
        if(o==null)return "null";
        if(o instanceof String[])return Arrays.toString((String[])o);
        if(o instanceof int[])return Arrays.toString((int[])o);
        if(o instanceof Object[])return Arrays.toString((Object[])o);
        return String.valueOf(o);
    }

    private void render(String s){ if(overlay!=null) overlay.setText(s); }

    @Override public void surfaceCreated(SurfaceHolder h){startPreviewIfReady();maybeStart();}
    @Override public void surfaceChanged(SurfaceHolder h,int f,int w,int he){}
    @Override public void surfaceDestroyed(SurfaceHolder h){previewStarted=false;}

    private void startPreviewIfReady(){
        if(normal==null||previewStarted||holder==null)return;
        try{normal.setPreviewDisplay(holder);normal.startPreview();previewStarted=true;}
        catch(Throwable t){revisionErr="PREVIEW "+rootCause(t);finishProbe();}
    }

    @Override public boolean onKeyDown(int keyCode,KeyEvent e){
        int scan=e.getScanCode();
        if(scan==SCAN_MENU||scan==SCAN_DELETE||keyCode==KeyEvent.KEYCODE_MENU||keyCode==KeyEvent.KEYCODE_DEL){
            try{restoreOriginal();}catch(Throwable ignored){}
            releaseCamera();finish();return true;
        }
        return true;
    }
    @Override public boolean onKeyUp(int keyCode,KeyEvent e){return true;}

    private void releaseCamera(){
        try{if(normal!=null&&previewStarted)normal.stopPreview();}catch(Throwable ignored){}
        try{if(cameraEx!=null)call(cameraEx,"release",new Class[0],new Object[0]);}catch(Throwable ignored){}
        normal=null;cameraEx=null;previewStarted=false;
    }

    @Override protected void onPause(){
        super.onPause();
        if(isFinishing()){try{restoreOriginal();}catch(Throwable ignored){} releaseCamera();}
    }

    private static Object call(Object o,String n,Class[] types,Object[] args)throws Exception{
        Method m=o.getClass().getMethod(n,types); return m.invoke(o,args);
    }
    private static String rootCause(Throwable t){
        Throwable c=t; while(c.getCause()!=null)c=c.getCause();
        return c.getClass().getSimpleName()+": "+String.valueOf(c.getMessage());
    }
}
