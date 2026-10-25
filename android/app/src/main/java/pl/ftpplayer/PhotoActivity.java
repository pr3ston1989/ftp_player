package pl.ftpplayer;
import android.app.*;
import android.os.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;

/** Bounded image decoding with folder navigation, pinch zoom, swipe and TV keys. */
public final class PhotoActivity extends Activity {
    private JSONArray images=new JSONArray(); private int index,generation; private boolean closed;
    private ImageView image; private TextView caption; private Bitmap bitmap; private float zoom=1f, downX,downY;
    private final Matrix matrix=new Matrix(); private ScaleGestureDetector scale;
    private final Handler ui=new Handler(Looper.getMainLooper());
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root=new LinearLayout(this); root.setOrientation(1); root.setBackgroundColor(MainActivity.BG); setContentView(root);
        LinearLayout bar=new LinearLayout(this); root.addView(bar);
        for(String title:new String[]{"←","Dopasuj","→","Zamknij"}) {
            Button b=new Button(this); b.setText(title); b.setTextColor(MainActivity.TEXT); b.setAllCaps(false);
            b.setOnClickListener(v->{ if(title.equals("←")) move(-1); else if(title.equals("→")) move(1); else if(title.equals("Zamknij")) finish(); else { zoom=1; fit(); } });
            bar.addView(b,new LinearLayout.LayoutParams(0,64,1));
        }
        image=new ImageView(this); image.setScaleType(ImageView.ScaleType.MATRIX); image.setFocusable(true); image.setContentDescription("Zdjęcie. Lewo i prawo: następne lub poprzednie.");
        root.addView(image,new LinearLayout.LayoutParams(-1,0,1));
        caption=new TextView(this); caption.setTextColor(MainActivity.MUTED); caption.setPadding(18,10,18,16); root.addView(caption);
        image.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->fit());
        scale=new ScaleGestureDetector(this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector detector) { zoom=Math.max(1f,Math.min(8f,zoom*detector.getScaleFactor())); fit(); return true; }
        });
        image.setOnTouchListener((v,event)-> {
            scale.onTouchEvent(event);
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN) { downX=event.getX(); downY=event.getY(); }
            if(event.getActionMasked()==MotionEvent.ACTION_MOVE && zoom>1 && event.getPointerCount()==1) {
                matrix.postTranslate(event.getX()-downX,event.getY()-downY); image.setImageMatrix(matrix); downX=event.getX(); downY=event.getY();
            }
            if(event.getActionMasked()==MotionEvent.ACTION_UP && zoom<=1 && Math.abs(event.getX()-downX)>100) move(event.getX()<downX?1:-1);
            return true;
        });
        image.setOnKeyListener((v,key,event)-> { if(event.getAction()!=KeyEvent.ACTION_DOWN) return false;
            if(key==KeyEvent.KEYCODE_DPAD_LEFT) { move(-1); return true; } if(key==KeyEvent.KEYCODE_DPAD_RIGHT) { move(1); return true; }
            if(key==KeyEvent.KEYCODE_DPAD_CENTER) { zoom=zoom==1?2:1; fit(); return true; } return false;
        });
        try {
            File file=new File(getIntent().getStringExtra("playlist_path"));
            if(file.length()>8*1024*1024) throw new IOException();
            try(FileInputStream input=new FileInputStream(file)) { ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] bytes=new byte[8192]; int n; while((n=input.read(bytes))>0) out.write(bytes,0,n); images=new JSONArray(out.toString("UTF-8")); }
            for(int i=0;i<images.length();i++) if(images.getJSONObject(i).optString("id").equals(getIntent().getStringExtra("file_id"))) index=i;
            if(state!=null) index=state.getInt("index",index);
            load(); image.requestFocus();
        } catch(Exception error) { caption.setText("Nie udało się otworzyć listy zdjęć"); }
    }
    private void move(int step) { if(images.length()>0) { index=(index+step+images.length())%images.length(); zoom=1; load(); } }
    private void load() {
        final int request=++generation; JSONObject entry=images.optJSONObject(index); if(entry==null) return;
        caption.setText("Wczytywanie: "+entry.optString("name")); image.setImageDrawable(null);
        ((AppState)getApplication()).workers.execute(()-> {
            Bitmap decoded=null; File cache=null;
            try {
                cache=File.createTempFile("photo-",".bin",getCacheDir());
                HttpURLConnection connection=(HttpURLConnection)new URL(entry.getString("url")).openConnection(); connection.setConnectTimeout(15000); connection.setReadTimeout(40000);
                try(InputStream input=connection.getInputStream(); FileOutputStream output=new FileOutputStream(cache)) {
                    byte[] buffer=new byte[65536]; int n,total=0;
                    while((n=input.read(buffer))>0) { total+=n; if(total>80*1024*1024||closed||request!=generation) throw new IOException(); output.write(buffer,0,n); }
                } finally { connection.disconnect(); }
                BitmapFactory.Options options=new BitmapFactory.Options(); options.inJustDecodeBounds=true; BitmapFactory.decodeFile(cache.getPath(),options);
                options.inSampleSize=1; while(options.outWidth/options.inSampleSize>2560||options.outHeight/options.inSampleSize>2560) options.inSampleSize*=2;
                options.inJustDecodeBounds=false; options.inPreferredConfig=Bitmap.Config.ARGB_8888; decoded=BitmapFactory.decodeFile(cache.getPath(),options);
                if(decoded==null) throw new IOException();
                try {
                    android.media.ExifInterface exif=new android.media.ExifInterface(cache.getPath());
                    int orientation=exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1);
                    Matrix rotation=new Matrix();
                    if(orientation==3) rotation.postRotate(180); if(orientation==6) rotation.postRotate(90); if(orientation==8) rotation.postRotate(270);
                    if(orientation==2) rotation.postScale(-1,1); if(orientation==4) rotation.postScale(1,-1);
                    if(!rotation.isIdentity()) { Bitmap rotated=Bitmap.createBitmap(decoded,0,0,decoded.getWidth(),decoded.getHeight(),rotation,true); if(rotated!=decoded) decoded.recycle(); decoded=rotated; }
                } catch(IOException ignored) {}
            } catch(Exception error) { if(decoded!=null) decoded.recycle(); decoded=null; }
            finally { if(cache!=null) cache.delete(); }
            final Bitmap result=decoded;
            ui.post(()-> { if(closed||request!=generation) { if(result!=null) result.recycle(); return; }
                if(bitmap!=null) bitmap.recycle(); bitmap=result;
                if(result==null) { caption.setText("Nie udało się odczytać obrazu. Użyj strzałek, aby przejść dalej."); return; }
                image.setImageBitmap(bitmap); caption.setText((index+1)+"/"+images.length()+"  ·  "+entry.optString("name")); fit();
            });
        });
    }
    private void fit() {
        if(bitmap==null||image.getWidth()==0) return;
        float ratio=Math.min((float)image.getWidth()/bitmap.getWidth(),(float)image.getHeight()/bitmap.getHeight())*zoom;
        matrix.reset(); matrix.postScale(ratio,ratio); matrix.postTranslate((image.getWidth()-bitmap.getWidth()*ratio)/2,(image.getHeight()-bitmap.getHeight()*ratio)/2); image.setImageMatrix(matrix);
    }
    @Override public void onSaveInstanceState(Bundle state) { state.putInt("index",index); super.onSaveInstanceState(state); }
    @Override protected void onDestroy() { closed=true; generation++; ui.removeCallbacksAndMessages(null); image.setImageDrawable(null); if(bitmap!=null) bitmap.recycle(); super.onDestroy(); }
}
