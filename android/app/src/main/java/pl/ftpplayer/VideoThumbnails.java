package pl.ftpplayer;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.*;
import android.util.LruCache;
import android.view.TextureView;
import org.json.JSONObject;
import org.videolan.libvlc.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** One silent libVLC decoder, three cached frames, bounded disk/RAM and stale-job cancellation. */
final class VideoThumbnails {
    private final Activity activity;
    private final TextureView texture;
    private final Runnable changed;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService disk=Executors.newSingleThreadExecutor();
    private final ExecutorService decoder=Executors.newSingleThreadExecutor();
    private final LruCache<String,Bitmap[]> memory=new LruCache<String,Bitmap[]>(24*1024*1024) {
        @Override protected int sizeOf(String key,Bitmap[] frames) { int bytes=0;for(Bitmap bitmap:frames)bytes+=bitmap.getByteCount();return bytes; }
    };
    private final File directory;
    private final Set<String> pending=new HashSet<>();
    private final Map<String,Long> failed=new HashMap<>();
    private final ArrayDeque<Job> queue=new ArrayDeque<>();
    private LibVLC engine; private MediaPlayer player; private Job current;
    private int generation, frame, saved; private long deadline, captureAt; private boolean seeking,closed,loaded,stopping;
    private final Bitmap[] captured=new Bitmap[3];
    private static final class Job { JSONObject account,entry;String key;int generation; Job(JSONObject a,JSONObject e,String k,int g){account=a;entry=e;key=k;generation=g;} }
    VideoThumbnails(Activity activity,TextureView texture,Runnable changed) {
        this.activity=activity;this.texture=texture;this.changed=changed;directory=new File(activity.getCacheDir(),"video-thumbnails-v1");directory.mkdirs();
    }
    String key(JSONObject account,JSONObject entry) { return LibraryStore.hash(LibraryStore.key(account)+":"+entry.optString("path")+":"+entry.optLong("size")+":"+entry.optLong("modified")+":v3"); }
    Bitmap[] get(JSONObject account,JSONObject entry) { return memory.get(key(account,entry)); }
    void request(JSONObject account,JSONObject entry) {
        String key=key(account,entry);
        if(closed||memory.get(key)!=null||pending.contains(key)||failed.containsKey(key)&&failed.get(key)>SystemClock.elapsedRealtime()||pending.size()>20)return;
        pending.add(key);final int requested=generation;
        disk.execute(()-> {
            Bitmap[] frames=new Bitmap[3];boolean valid=true;
            for(int i=0;i<3;i++) {
                File file=new File(directory,key+"-"+i+".jpg");
                long ttl=entry.optLong("modified")>0?30L*86400000:7L*86400000;
                if(!file.isFile()||System.currentTimeMillis()-file.lastModified()>ttl){valid=false;break;}
                frames[i]=BitmapFactory.decodeFile(file.getPath());if(frames[i]==null){valid=false;break;}
            }
            final boolean found=valid;
            ui.post(()-> {
                if(closed||requested!=generation){pending.remove(key);return;}
                if(found){memory.put(key,frames);pending.remove(key);changed.run();}
                else {queue.add(new Job(account,entry,key,requested));next();}
            });
        });
    }
    private void next() {
        if(closed||stopping||current!=null||queue.isEmpty())return;
        current=queue.removeFirst();frame=0;saved=0;seeking=false;Arrays.fill(captured,null);deadline=SystemClock.elapsedRealtime()+25000;
        ui.post(poll);
    }
    private final Runnable poll=new Runnable(){public void run(){
        if(closed||current==null)return;
        if(SystemClock.elapsedRealtime()>deadline){android.util.Log.w("FTPThumb","Thumbnail timeout, captured frames="+frame);finish(false);return;}
        if(!texture.isAvailable()){ui.postDelayed(this,150);return;}
        try {
            if(engine==null){ArrayList<String> options=new ArrayList<>();options.add("--no-audio");options.add("--no-spu");options.add("--no-stats");engine=new LibVLC(activity,options);player=new MediaPlayer(engine);}
            if(!loaded){
                player.getVLCVout().setVideoView(texture);player.getVLCVout().attachViews();player.getVLCVout().setWindowSize(640,360);
                String url=((AppState)activity.getApplication()).server.register(current.account,current.entry);
                Media media=new Media(engine,android.net.Uri.parse(url));media.setHWDecoderEnabled(false,false);media.addOption(":no-audio");media.addOption(":network-caching=500");media.addOption(":no-spu");player.setMedia(media);loaded=true;media.release();player.play();ui.postDelayed(this,150);return;
            }
            long duration=player.getLength();
            if(duration<=0){ui.postDelayed(this,150);return;}
            // Jeden skok: starsze demuksery AVI potrafią utknąć przy kolejnych seekach.
            float fraction=.45f+((current.key.hashCode()&255)/255f-.5f)*.06f;
            if(!seeking){if(!player.isPlaying())player.play();player.setTime((long)(duration*fraction));seeking=true;captureAt=SystemClock.elapsedRealtime()+650;}
            else if(SystemClock.elapsedRealtime()>=captureAt && player.getTime()>0){
                Bitmap bitmap=texture.getBitmap(640,360);
                if(bitmap!=null){
                    captured[frame]=bitmap;final Job job=current;final int index=frame;
                    disk.execute(()-> {
                        boolean success=true;File target=new File(directory,job.key+"-"+index+".jpg"), temp=new File(directory,job.key+"-"+index+".tmp");
                        try(FileOutputStream out=new FileOutputStream(temp)){bitmap.compress(Bitmap.CompressFormat.JPEG,78,out);}
                        catch(Exception error){success=false;}
                        if(success&&!temp.renameTo(target))success=false;temp.delete();final boolean ok=success;
                        ui.post(()-> {if(current!=job||closed)return;if(!ok){finish(false);return;}saved++;if(saved==3)finish(true);});
                    });
                    frame++;captureAt=SystemClock.elapsedRealtime()+700;
                    if(frame==3){player.pause();return;}
                }
            }
            ui.postDelayed(this,150);
        } catch(Exception error){android.util.Log.w("FTPThumb","Thumbnail decoder: "+error.getClass().getSimpleName());finish(false);}
    }};
    private void stopPlayer(Runnable after){
        if(player==null){if(after!=null)after.run();return;}
        if(stopping){if(after!=null)decoder.execute(()->ui.post(after));return;}
        player.getVLCVout().detachViews();stopping=true;final MediaPlayer active=player;
        decoder.execute(()->{
            try{active.stop();active.setMedia(null);}catch(Exception ignored){}
            ui.post(()->{loaded=false;stopping=false;if(after!=null)after.run();else next();});
        });
    }
    private void finish(boolean success){
        Job job=current;if(job==null)return;ui.removeCallbacks(poll);current=null;
        if(success&&job.generation==generation){memory.put(job.key,captured.clone());changed.run();disk.execute(this::trim);}
        else failed.put(job.key,SystemClock.elapsedRealtime()+300000);
        pending.remove(job.key);stopPlayer(null);
    }
    private void trim(){File[] files=directory.listFiles();if(files==null)return;Arrays.sort(files,Comparator.comparingLong(File::lastModified));long total=0;for(File f:files)total+=f.length();for(File f:files){if(total<=128L*1024*1024)break;total-=f.length();f.delete();}}
    String debug(){return "frame="+frame+",saved="+saved+",loaded="+loaded+",stopping="+stopping+",generation="+generation+",queue="+queue.size()+",current="+(current==null?"none":current.entry.optString("name"))+",keys="+memory.snapshot().keySet();}
    void cancel(){generation++;queue.clear();pending.clear();ui.removeCallbacks(poll);current=null;if(loaded&&!stopping)stopPlayer(null);}
    void close(){
        closed=true;cancel();disk.shutdown();
        stopPlayer(()->{
            final MediaPlayer old=player;final LibVLC oldEngine=engine;player=null;engine=null;
            if(old!=null)old.getVLCVout().detachViews();
            decoder.execute(()->{if(old!=null)old.release();if(oldEngine!=null)oldEngine.release();decoder.shutdown();});
        });
    }
}
