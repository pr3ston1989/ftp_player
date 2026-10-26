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

/** One silent libVLC decoder, ten distributed cached frames, bounded disk/RAM and stale-job cancellation. */
final class VideoThumbnails {
    static final int FRAME_COUNT=1;
    static final int[] FRAME_COUNTS={0,1,3,5,10,20};
    static int normalizeCount(int value){for(int allowed:FRAME_COUNTS)if(value==allowed)return value;return FRAME_COUNT;}
    static float fraction(int index){return fraction(index,FRAME_COUNT);}
    static float fraction(int index,int count){return (index+.5f)/count;}
    private int frameCount=FRAME_COUNT;
    private final Activity activity;
    private final TextureView texture;
    private final Runnable changed;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService disk=Executors.newSingleThreadExecutor();
    private final ExecutorService decoder=Executors.newSingleThreadExecutor();
    private final LruCache<String,Bitmap[]> memory=new LruCache<String,Bitmap[]>(48*1024*1024) {
        @Override protected int sizeOf(String key,Bitmap[] frames) { int bytes=0;for(Bitmap bitmap:frames)bytes+=bitmap.getByteCount();return bytes; }
    };
    private final File directory;
    private final Set<String> pending=new HashSet<>();
    private final Map<String,Long> failed=new HashMap<>();
    private Set<String> visibleKeys;
    private final ArrayDeque<Job> queue=new ArrayDeque<>();
    private LibVLC engine; private MediaPlayer player; private volatile Job current;
    private volatile int generation; private int frame, saved; private long deadline, captureAt, duration, initialTexture; private boolean seeking,loaded,stopping; private volatile boolean closed;
    long lastTransferBytes,lastDuration; long[] lastSampleTimes;
    private long[] sampleTimes=new long[FRAME_COUNT];
    private Bitmap[] captured=new Bitmap[FRAME_COUNT];
    private static final class Job { JSONObject account,entry;String key;int generation; String url; Job(JSONObject a,JSONObject e,String k,int g){account=a;entry=e;key=k;generation=g;} }
    VideoThumbnails(Activity activity,TextureView texture,Runnable changed) {
        this.activity=activity;this.texture=texture;this.changed=changed;directory=new File(activity.getCacheDir(),"video-thumbnails-v1");directory.mkdirs();
    }
    void setFrameCount(int value){int next=normalizeCount(value);if(next==frameCount)return;cancel();frameCount=next;sampleTimes=new long[next];captured=new Bitmap[next];memory.evictAll();}
    int frameCount(){return frameCount;}
    String key(JSONObject account,JSONObject entry) { return LibraryStore.hash(LibraryStore.key(account)+":"+entry.optString("path")+":"+entry.optLong("size")+":"+entry.optLong("modified")+":ten-v4"+(frameCount==10?"":":"+frameCount)); }
    Bitmap[] get(JSONObject account,JSONObject entry) { return frameCount==0?null:memory.get(key(account,entry)); }
    void request(JSONObject account,JSONObject entry) {
        if(frameCount==0||closed)return;
        String key=key(account,entry);
        if(closed||memory.get(key)!=null||pending.contains(key)||failed.containsKey(key)&&failed.get(key)>SystemClock.elapsedRealtime()||pending.size()>20)return;
        pending.add(key);final int requested=generation, count=frameCount;
        disk.execute(()-> {
            Bitmap[] frames=new Bitmap[count];boolean valid=true;
            for(int i=0;i<count;i++) {
                File file=new File(directory,key+"-"+i+".jpg");
                long ttl=entry.optLong("modified")>0?30L*86400000:7L*86400000;
                if(!file.isFile()||System.currentTimeMillis()-file.lastModified()>ttl){valid=false;break;}
                frames[i]=BitmapFactory.decodeFile(file.getPath());if(frames[i]==null){valid=false;break;}
            }
            final boolean found=valid;
            ui.post(()-> {
                if(closed||requested!=generation)return;
                if(visibleKeys!=null&&!visibleKeys.contains(key)){pending.remove(key);return;}
                if(found){memory.put(key,frames);pending.remove(key);changed.run();}
                else {queue.add(new Job(account,entry,key,requested));next();}
            });
        });
    }
    void retainVisible(JSONObject account,List<JSONObject> entries){
        Set<String> keys=new HashSet<>();for(JSONObject entry:entries)keys.add(key(account,entry));
        if(current!=null&&!keys.contains(current.key))cancel();
        visibleKeys=keys;
        for(Iterator<Job> iterator=queue.iterator();iterator.hasNext();){Job job=iterator.next();if(!keys.contains(job.key)){pending.remove(job.key);iterator.remove();}}
    }
    private void next() {
        if(closed||frameCount==0||stopping||current!=null||queue.isEmpty())return;
        current=queue.removeFirst();frame=0;saved=0;seeking=false;Arrays.fill(captured,null);duration=0;deadline=SystemClock.elapsedRealtime()+15000;
        final Job job=current;decoder.execute(()->nativeFrames(job));
    }
    private void nativeFrames(Job job){
        if(closed||generation!=job.generation)return;
        Bitmap[] frames=new Bitmap[frameCount];long length=0;boolean success=false;
        android.media.MediaMetadataRetriever retriever=new android.media.MediaMetadataRetriever();
        try{
            if(job.url==null)job.url=((AppState)activity.getApplication()).server.register(job.account,job.entry,true);
            retriever.setDataSource(job.url,new HashMap<String,String>());
            length=Long.parseLong(retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION));
            for(int i=0;i<frames.length;i++){
                if(closed||generation!=job.generation)throw new InterruptedException();
                long at=(long)(length*fraction(i,frames.length))*1000;
                Bitmap bitmap=Build.VERSION.SDK_INT>=27?retriever.getScaledFrameAtTime(at,android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,480,270):retriever.getFrameAtTime(at,android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if(bitmap==null)throw new IOException("Unsupported thumbnail decoder");
                Bitmap scaled=Bitmap.createBitmap(480,270,Bitmap.Config.ARGB_8888);android.graphics.Canvas canvas=new android.graphics.Canvas(scaled);canvas.drawColor(android.graphics.Color.BLACK);float ratio=Math.min(480f/bitmap.getWidth(),270f/bitmap.getHeight());float width=bitmap.getWidth()*ratio,height=bitmap.getHeight()*ratio;canvas.drawBitmap(bitmap,null,new android.graphics.RectF((480-width)/2,(270-height)/2,(480+width)/2,(270+height)/2),new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));bitmap.recycle();frames[i]=scaled;
            }
            success=true;
        }catch(Exception ignored){}finally{try{retriever.release();}catch(Exception ignored){}if(closed||generation!=job.generation)((AppState)activity.getApplication()).server.unregister(job.url);}
        final boolean ok=success;final long mediaLength=length;
        ui.post(()->{
            if(closed||current!=job){for(Bitmap bitmap:frames)if(bitmap!=null)bitmap.recycle();return;}
            if(!ok){for(Bitmap bitmap:frames)if(bitmap!=null)bitmap.recycle();deadline=SystemClock.elapsedRealtime()+15000;ui.post(poll);return;}
            duration=mediaLength;System.arraycopy(frames,0,captured,0,frames.length);frame=frames.length;for(int i=0;i<frames.length;i++)sampleTimes[i]=(long)(duration*fraction(i,frames.length));
            disk.execute(()->{
                boolean written=true;
                for(int i=0;i<frames.length;i++){File target=new File(directory,job.key+"-"+i+".jpg"),temp=new File(directory,job.key+"-"+i+".tmp");try(FileOutputStream out=new FileOutputStream(temp)){if(!frames[i].compress(Bitmap.CompressFormat.JPEG,78,out))throw new IOException();}catch(Exception error){written=false;}if(written&&!temp.renameTo(target))written=false;temp.delete();if(!written)break;}
                final boolean complete=written;ui.post(()->{if(current==job&&!closed)finish(complete);});
            });
        });
    }
    private long targetTime(){return Math.min((long)(duration*fraction(frame,frameCount)),Math.max(0,duration-1000));}
    private final Runnable poll=new Runnable(){public void run(){
        if(closed||current==null)return;
        if(SystemClock.elapsedRealtime()>deadline){android.util.Log.w("FTPThumb","Thumbnail timeout, captured frames="+frame+",time="+player.getTime()+",target="+targetTime());finish(false);return;}
        if(!texture.isAvailable()){ui.postDelayed(this,150);return;}
        try {
            if(engine==null){ArrayList<String> options=new ArrayList<>();options.add("--no-audio");options.add("--no-spu");options.add("--no-stats");options.add("--prefetch-buffer-size=256");options.add("--prefetch-read-size=32768");options.add("--prefetch-seek-threshold=65536");engine=new LibVLC(activity,options);player=new MediaPlayer(engine);}
            if(!loaded){
                player.getVLCVout().setVideoView(texture);player.getVLCVout().attachViews();player.getVLCVout().setWindowSize(640,360);
                if(current.url==null)current.url=((AppState)activity.getApplication()).server.register(current.account,current.entry,true);
                String url=current.url;initialTexture=texture.getSurfaceTexture().getTimestamp();
                Media media=new Media(engine,android.net.Uri.parse(url));media.setHWDecoderEnabled(false,false);if(duration>0)media.addOption(":start-time="+(targetTime()/1000.0));media.addOption(":prefetch-buffer-size=256");media.addOption(":prefetch-read-size=32768");media.addOption(":prefetch-seek-threshold=65536");media.addOption(":no-audio");media.addOption(":network-caching=500");media.addOption(":no-spu");player.setMedia(media);loaded=true;media.release();player.play();seeking=duration>0;captureAt=SystemClock.elapsedRealtime()+350;ui.postDelayed(this,150);return;
            }
            if(duration<=0)duration=player.getLength();
            if(duration<=0){ui.postDelayed(this,150);return;}
            // AVI jest otwierane ponownie dla każdej klatki; pozostałe kontenery używają seek.
            long targetTime=targetTime();
            if(!seeking){player.setTime((long)(targetTime));seeking=true;captureAt=SystemClock.elapsedRealtime()+350;}
            else if(SystemClock.elapsedRealtime()>=captureAt && player.getTime()>=targetTime+120
                    && texture.getSurfaceTexture().getTimestamp()!=initialTexture){
                Bitmap bitmap=texture.getBitmap(480,270);
                if(bitmap!=null){
                    sampleTimes[frame]=player.getTime();captured[frame]=bitmap;final Job job=current;final int index=frame;
                    disk.execute(()-> {
                        boolean success=true;File target=new File(directory,job.key+"-"+index+".jpg"), temp=new File(directory,job.key+"-"+index+".tmp");
                        try(FileOutputStream out=new FileOutputStream(temp)){bitmap.compress(Bitmap.CompressFormat.JPEG,78,out);}
                        catch(Exception error){success=false;}
                        if(success&&!temp.renameTo(target))success=false;temp.delete();final boolean ok=success;
                        ui.post(()-> {if(current!=job||closed)return;if(!ok){finish(false);return;}saved++;if(saved==frameCount)finish(true);});
                    });
                    frame++;
                    if(frame==frameCount){player.pause();return;}
                    ui.removeCallbacks(poll);
                    seeking=false;initialTexture=texture.getSurfaceTexture().getTimestamp();deadline=SystemClock.elapsedRealtime()+15000;
                    if(current.entry.optString("name").toLowerCase(Locale.ROOT).endsWith(".avi"))
                        stopPlayer(()->{if(!closed&&current!=null)ui.post(poll);});
                    else ui.postDelayed(poll,100);
                    return;
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
        if(success&&job.generation==generation){lastTransferBytes=((AppState)activity.getApplication()).server.previewUsage(job.url);lastDuration=duration;lastSampleTimes=sampleTimes.clone();memory.put(job.key,captured.clone());changed.run();disk.execute(this::trim);}
        else failed.put(job.key,SystemClock.elapsedRealtime()+300000);
        android.util.Log.i("FTPThumb","Thumbnail result="+success+",frames="+frame+",payload="+((AppState)activity.getApplication()).server.previewUsage(job.url==null?"http://localhost/none":job.url));
        ((AppState)activity.getApplication()).server.unregister(job.url);
        pending.remove(job.key);stopPlayer(null);
    }
    private void trim(){File[] files=directory.listFiles();if(files==null)return;Arrays.sort(files,Comparator.comparingLong(File::lastModified));long total=0;for(File f:files)total+=f.length();for(File f:files){if(total<=128L*1024*1024)break;total-=f.length();f.delete();}}
    String debug(){return "frame="+frame+",saved="+saved+",loaded="+loaded+",stopping="+stopping+",generation="+generation+",queue="+queue.size()+",current="+(current==null?"none":current.entry.optString("name"))+",keys="+memory.snapshot().keySet();}
    void cancel(){generation++;queue.clear();pending.clear();ui.removeCallbacks(poll);if(current!=null)((AppState)activity.getApplication()).server.unregister(current.url);current=null;if(loaded&&!stopping)stopPlayer(null);}
    void close(){
        closed=true;cancel();disk.shutdown();
        stopPlayer(()->{
            final MediaPlayer old=player;final LibVLC oldEngine=engine;player=null;engine=null;
            if(old!=null)old.getVLCVout().detachViews();
            decoder.execute(()->{if(old!=null)old.release();if(oldEngine!=null)oldEngine.release();decoder.shutdown();});
        });
    }
}
