package pl.ftpplayer;

import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import static org.junit.Assert.*;
import android.app.Activity;
import android.content.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicReference;

/** Local fixture tests on an emulator, including the embedded VLC boundary. */
public class PlayerSmokeTest {
    android.app.Instrumentation getInstrumentation() { return InstrumentationRegistry.getInstrumentation(); }
    private String originalProfiles,originalDownloads;
    private java.util.Set<Integer> originalNotificationIds;
    private java.util.Map<String,?> originalBrowser;
    @Before public void preserveProfiles() {
        originalNotificationIds=new java.util.HashSet<>();android.app.NotificationManager notifications=(android.app.NotificationManager)getInstrumentation().getTargetContext().getSystemService(Context.NOTIFICATION_SERVICE);for(android.service.notification.StatusBarNotification n:notifications.getActiveNotifications())originalNotificationIds.add(n.getId());
        originalBrowser=getInstrumentation().getTargetContext().getSharedPreferences("MainActivity",0).getAll();
        originalDownloads=getInstrumentation().getTargetContext().getSharedPreferences("downloads",0).getString("history","[]");
        originalProfiles=getInstrumentation().getTargetContext().getSharedPreferences("accounts",0).getString("profiles","[]");
    }
    @After public void restoreProfiles() {
        getInstrumentation().runOnMainSync(()->getInstrumentation().getTargetContext().stopService(new Intent(getInstrumentation().getTargetContext(),DownloadService.class)));getInstrumentation().waitForIdleSync();
        android.app.NotificationManager notifications=(android.app.NotificationManager)getInstrumentation().getTargetContext().getSystemService(Context.NOTIFICATION_SERVICE);for(android.service.notification.StatusBarNotification n:notifications.getActiveNotifications())if(originalNotificationIds!=null&&!originalNotificationIds.contains(n.getId()))notifications.cancel(n.getId());
        if(originalBrowser!=null){android.content.SharedPreferences.Editor edit=getInstrumentation().getTargetContext().getSharedPreferences("MainActivity",0).edit().clear();for(java.util.Map.Entry<String,?> entry:originalBrowser.entrySet()){Object v=entry.getValue();if(v instanceof String)edit.putString(entry.getKey(),(String)v);else if(v instanceof Boolean)edit.putBoolean(entry.getKey(),(Boolean)v);else if(v instanceof Integer)edit.putInt(entry.getKey(),(Integer)v);else if(v instanceof Long)edit.putLong(entry.getKey(),(Long)v);}edit.commit();}
        if(originalDownloads!=null)getInstrumentation().getTargetContext().getSharedPreferences("downloads",0).edit().putString("history",originalDownloads).commit();
        if(originalProfiles!=null) getInstrumentation().getTargetContext().getSharedPreferences("accounts",0).edit().putString("profiles",originalProfiles).commit();
    }
    JSONObject account(String protocol) throws Exception {
        return new JSONObject().put("id","fixture-"+protocol).put("name","Test "+protocol)
            .put("address",protocol+"://"+InstrumentationRegistry.getArguments().getString("host","10.0.2.2")+":"+(protocol.equals("ftp")?"21210":"8767")+"/")
            .put("username",protocol.equals("ftp")?"test":"").put("password",protocol.equals("ftp")?"test":"");
    }
    @Test public void testHttpAndFtpSeek() throws Exception {
        AppState app=(AppState)getInstrumentation().getTargetContext().getApplicationContext();
        for(String protocol:new String[]{"http","ftp"}) {
            RemoteClient client=new RemoteClient(account(protocol)); JSONArray entries=client.list("/");
            assertTrue(entries.length()>=4); JSONObject selected=null;
            for(int i=0;i<entries.length();i++) if(entries.getJSONObject(i).optString("name").equals("sample.mp4")) selected=entries.getJSONObject(i);
            assertNotNull(selected); if(protocol.equals("http"))selected.put("size",123).put("sizeExact",false); String url=app.server.register(account(protocol),selected);
            HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection(); connection.setRequestProperty("Range","bytes=100-199");
            assertEquals(206,connection.getResponseCode()); assertEquals(100,connection.getContentLength());
            try(InputStream input=connection.getInputStream()) { int total=0; while(input.read()!=-1) total++; assertEquals(100,total); }
            connection.disconnect();
        }
    }
    @Test public void testPreviewRejectsServerWithoutRange() throws Exception {
        JSONObject entry=new JSONObject().put("path","/no-range/sample.mp4");
        try(InputStream ignored=new RemoteClient(account("http")).read(entry,100,true)) { fail("Preview must not discard the start of a remote file"); }
        catch(IOException expected) { assertTrue(expected.getMessage().contains("Range")); }
        try(InputStream playback=new RemoteClient(account("http")).read(entry,100,false)) { assertTrue(playback.read()>=0); }
    }
    @Test public void testAccountsEncryptedAndEditable() throws Exception {
        AccountStore store=new AccountStore(getInstrumentation().getTargetContext());
        JSONArray rows=new JSONArray().put(account("http")).put(account("ftp")); store.save(rows);
        assertEquals(2,store.load().length());
        String raw=getInstrumentation().getTargetContext().getSharedPreferences("accounts",0).getString("profiles","");
        assertFalse(raw.contains("\"password\"")); assertEquals("test",store.load().getJSONObject(1).getString("password"));
    }
    @Test public void testNativePlaybackFormats() throws Exception {
        Context context=getInstrumentation().getTargetContext(); AppState app=(AppState)context.getApplicationContext();
        RemoteClient client=new RemoteClient(account("http")); JSONArray rows=client.list("/");
        for(String filename:new String[]{"sample.mp4","hevc-main10.mkv","legacy.avi","mpeg2.mpg","windows-media.wmv","xvid.avi","divx.avi","realvideo.rm","quicktime.mov"}) {
            JSONObject entry=null;
            for(int i=0;i<rows.length();i++) if(rows.getJSONObject(i).optString("name").equals(filename)) entry=rows.getJSONObject(i);
            assertNotNull(entry);
            Intent intent=new Intent(context,NativePlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("url",app.server.register(account("http"),entry)).putExtra("file_id",entry.getString("id"))
                .putExtra("title",filename).putExtra("size",client.size(entry));
            Activity activity=getInstrumentation().startActivitySync(intent);
            java.lang.reflect.Field field=NativePlayerActivity.class.getDeclaredField("player"); field.setAccessible(true);
            long deadline=android.os.SystemClock.elapsedRealtime()+18000; boolean advanced=false;
            while(android.os.SystemClock.elapsedRealtime()<deadline) {
                org.videolan.libvlc.MediaPlayer player=(org.videolan.libvlc.MediaPlayer)field.get(activity);
                if(player!=null) player.setVolume(0);
                if(player!=null&&player.getTime()>700) { advanced=true; break; }
                Thread.sleep(150);
            }
            assertTrue("Playback did not advance: "+filename,advanced);
            org.videolan.libvlc.MediaPlayer playing=(org.videolan.libvlc.MediaPlayer)field.get(activity);
            org.videolan.libvlc.interfaces.IMedia media=playing.getMedia();
            org.videolan.libvlc.interfaces.IMedia.Stats stats=media.getStats(); media.release();
            assertNotNull(stats);
            assertTrue("No decoded video: "+filename,stats.decodedVideo>0||stats.displayedPictures>0);
            assertTrue("No decoded audio: "+filename,stats.decodedAudio>0);
            getInstrumentation().runOnMainSync(activity::finish); Thread.sleep(400);
        }
    }
    @Test public void testBrowserAndPhotoLaunch() throws Exception {
        Context context=getInstrumentation().getTargetContext();
        new AccountStore(context).save(new JSONArray().put(account("http")));
        Activity browser=getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Thread.sleep(1500); assertFalse(browser.isFinishing());
        getInstrumentation().runOnMainSync(browser::finish);
    }
    @Test public void testPhotoFolderNavigation() throws Exception {
        Context context=getInstrumentation().getTargetContext(); AppState app=(AppState)context.getApplicationContext();
        JSONArray rows=new RemoteClient(account("http")).list("/"), photos=new JSONArray();
        for(int i=0;i<rows.length();i++) { JSONObject row=rows.getJSONObject(i); if(MainActivity.kind(row).equals("image")) { row.put("url",app.server.register(account("http"),row)); photos.put(row); } }
        assertTrue(photos.length()>=2);
        File file=new File(context.getCacheDir(),"photo-test.json");
        try(FileOutputStream out=new FileOutputStream(file)) { out.write(photos.toString().getBytes("UTF-8")); }
        Activity photo=getInstrumentation().startActivitySync(new Intent(context,PhotoActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("file_id",photos.getJSONObject(0).getString("id")).putExtra("playlist_path",file.getAbsolutePath()));
        java.lang.reflect.Field bitmap=PhotoActivity.class.getDeclaredField("bitmap"); bitmap.setAccessible(true);
        long deadline=android.os.SystemClock.elapsedRealtime()+10000;
        while(bitmap.get(photo)==null&&android.os.SystemClock.elapsedRealtime()<deadline) Thread.sleep(100);
        assertNotNull(bitmap.get(photo));
        java.lang.reflect.Method move=PhotoActivity.class.getDeclaredMethod("move",int.class); move.setAccessible(true);
        getInstrumentation().runOnMainSync(()-> { try { move.invoke(photo,1); } catch(Exception error) { throw new RuntimeException(error); } });
        Thread.sleep(1500); assertNotNull(bitmap.get(photo));
        java.lang.reflect.Field index=PhotoActivity.class.getDeclaredField("index"); index.setAccessible(true); assertEquals(1,index.getInt(photo));
        getInstrumentation().runOnMainSync(photo::finish);
    }
    @Test public void testTvFocusableBrowser() throws Exception {
        Context context=getInstrumentation().getTargetContext();
        new AccountStore(context).save(new JSONArray().put(account("http")));
        MainActivity browser=(MainActivity)getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Thread.sleep(1200);
        java.lang.reflect.Field tv=MainActivity.class.getDeclaredField("tv"), list=MainActivity.class.getDeclaredField("items"); tv.setAccessible(true); list.setAccessible(true);
        java.lang.reflect.Method build=MainActivity.class.getDeclaredMethod("build"),render=MainActivity.class.getDeclaredMethod("render"); build.setAccessible(true);render.setAccessible(true);
        getInstrumentation().runOnMainSync(()-> { try { tv.setBoolean(browser,true);build.invoke(browser);render.invoke(browser);android.widget.ListView files=(android.widget.ListView)list.get(browser);files.requestFocus();files.setSelection(0); } catch(Exception error) { throw new RuntimeException(error); } });
        getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN); Thread.sleep(250);
        assertTrue(((android.widget.ListView)list.get(browser)).getSelectedItemPosition()>=0);
        getInstrumentation().runOnMainSync(browser::finish);
    }
    @Test public void testSortingAndBookmarks() throws Exception {
        JSONObject profile=account("http");LibraryStore library=new LibraryStore(getInstrumentation().getTargetContext());
        library.visited(profile,"/last");assertEquals("/last",library.startup(profile));library.start(profile,"/start");assertEquals("/start",library.startup(profile));library.start(profile,"");
        JSONObject file=new JSONObject().put("name","movie.mov").put("path","/movie.mov").put("size",10);
        boolean before=library.favorite(profile,"/movie.mov",false);library.toggle(profile,file,false);assertEquals(!before,library.favorite(profile,"/movie.mov",false));library.toggle(profile,file,false);
        java.util.ArrayList<JSONObject> rows=new java.util.ArrayList<>();rows.add(new JSONObject().put("name","film10.mov").put("size",20).put("metadataKnown",true));rows.add(new JSONObject().put("name","film2.mkv").put("size",10).put("metadataKnown",true));rows.add(new JSONObject().put("name","missing.avi"));
        LibraryStore.sort(rows,"name",false);assertEquals("film2.mkv",rows.get(0).getString("name"));LibraryStore.sort(rows,"size",true);assertEquals("film10.mov",rows.get(0).getString("name"));assertEquals("missing.avi",rows.get(2).getString("name"));
        long[] parsed=RemoteClient.indexMetadata("2026-10-25 12:34 1.5M");assertEquals(1572864,parsed[0]);assertTrue(parsed[1]>0);
    }
    @Test public void testLandscapeLeavesRoomForFiles() throws Exception {
        Context context=getInstrumentation().getTargetContext();new AccountStore(context).save(new JSONArray().put(account("http")));
        Activity first=getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        android.app.Instrumentation.ActivityMonitor monitor=getInstrumentation().addMonitor(MainActivity.class.getName(),null,false);
        getInstrumentation().runOnMainSync(()->first.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
        Activity rotated=getInstrumentation().waitForMonitorWithTimeout(monitor,6000);if(rotated==null)rotated=first;
        Thread.sleep(1200);java.lang.reflect.Field field=MainActivity.class.getDeclaredField("items");field.setAccessible(true);android.widget.ListView list=(android.widget.ListView)field.get(rotated);
        assertTrue("No space for the file list",list.getHeight()/rotated.getResources().getDisplayMetrics().density>=130);
        final Activity activity=rotated;getInstrumentation().runOnMainSync(activity::finish);getInstrumentation().removeMonitor(monitor);
    }
    private void showFile(MainActivity browser,String path) throws Exception {
        java.lang.reflect.Field visible=MainActivity.class.getDeclaredField("visible"),items=MainActivity.class.getDeclaredField("items");visible.setAccessible(true);items.setAccessible(true);
        getInstrumentation().runOnMainSync(()->{try{java.util.List<JSONObject> rows=(java.util.List<JSONObject>)visible.get(browser);for(int i=0;i<rows.size();i++)if(path.equals(rows.get(i).optString("path"))){((android.widget.ListView)items.get(browser)).setSelection(i);break;}}catch(Exception error){throw new RuntimeException(error);}});
    }
    @Test public void testTenDistributedCachedVideoFrames() throws Exception {
        Context context=getInstrumentation().getTargetContext();JSONObject profile=account("http");new AccountStore(context).save(new JSONArray().put(profile));
        context.getSharedPreferences("MainActivity",0).edit().putString("sort","name").putBoolean("descending",false).putInt("previewFrames",10).commit();
        MainActivity browser=(MainActivity)getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        java.lang.reflect.Field rowsField=MainActivity.class.getDeclaredField("entries"), thumbsField=MainActivity.class.getDeclaredField("thumbnails");rowsField.setAccessible(true);thumbsField.setAccessible(true);
        long deadline=android.os.SystemClock.elapsedRealtime()+150000;android.graphics.Bitmap[] frames=null;JSONObject selected=null;boolean scrolled=false;
        while(android.os.SystemClock.elapsedRealtime()<deadline&&frames==null){
            JSONArray rows=(JSONArray)rowsField.get(browser);
            for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row.optString("name").equals(InstrumentationRegistry.getArguments().getString("thumbnailFile","divx.avi"))){selected=row;break;}}
            if(selected!=null){if(!scrolled||selected.optBoolean("sizeExact")){showFile(browser,selected.optString("path"));scrolled=true;}frames=((VideoThumbnails)thumbsField.get(browser)).get(profile,selected);
                java.lang.reflect.Field failures=VideoThumbnails.class.getDeclaredField("failed");failures.setAccessible(true);VideoThumbnails manager=(VideoThumbnails)thumbsField.get(browser);if(((java.util.Map<?,?>)failures.get(manager)).containsKey(manager.key(profile,selected)))break;}
            Thread.sleep(200);
        }
        assertNotNull("No thumbnails: "+(selected==null?"no selected video":selected.toString())+"; "+((VideoThumbnails)thumbsField.get(browser)).debug(),frames);assertEquals(10,frames.length);
        VideoThumbnails decoded=(VideoThumbnails)thumbsField.get(browser);if(decoded.lastDuration>20000){for(int i=0;i<10;i++)assertEquals("Frame position "+i,VideoThumbnails.fraction(i,10),decoded.lastSampleTimes[i]/(float)decoded.lastDuration,.035f);assertTrue(decoded.lastTransferBytes<selected.optLong("size"));}
        System.out.println("Thumbnail payload="+decoded.lastTransferBytes+", duration="+decoded.lastDuration+", sample times="+java.util.Arrays.toString(decoded.lastSampleTimes));
        java.util.Set<Integer> colors=new java.util.HashSet<>();for(int x=0;x<320;x+=10)for(int y=0;y<180;y+=10)colors.add(frames[0].getPixel(x,y));assertTrue("Thumbnail is blank",colors.size()>8);
        int changedPixels=0;for(int x=0;x<frames[0].getWidth();x+=5)for(int y=0;y<frames[0].getHeight();y+=5)if(frames[0].getPixel(x,y)!=frames[9].getPixel(x,y))changedPixels++;assertTrue("Slideshow frames are identical",changedPixels>8);
        File directory=new File(context.getCacheDir(),"video-thumbnails-v1");String key=((VideoThumbnails)thumbsField.get(browser)).key(profile,selected);assertTrue(new File(directory,key+"-9.jpg").isFile());
        long cachedAt=new File(directory,key+"-9.jpg").lastModified();
        getInstrumentation().runOnMainSync(browser::finish);
        MainActivity again=(MainActivity)getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Thread.sleep(1000);showFile(again,selected.optString("path"));
        android.graphics.Bitmap[] reused=null;deadline=android.os.SystemClock.elapsedRealtime()+12000;
        while(reused==null&&android.os.SystemClock.elapsedRealtime()<deadline){reused=((VideoThumbnails)thumbsField.get(again)).get(profile,selected);Thread.sleep(150);}
        assertNotNull("Disk cache was not reused",reused);assertEquals(cachedAt,new File(directory,key+"-9.jpg").lastModified());
        JSONObject changed=new JSONObject(selected.toString()).put("modified",selected.optLong("modified")+1000);assertNotEquals(key,((VideoThumbnails)thumbsField.get(again)).key(profile,changed));
        for(int i=0;i<10;i++)assertEquals((i+.5f)/10,VideoThumbnails.fraction(i,10),.0001f);
        getInstrumentation().runOnMainSync(again::finish);
    }
    @Test public void testPreviewCountSettingsPersistAndGenerate() throws Exception {
        Context context=getInstrumentation().getTargetContext();JSONObject profile=account("http");new AccountStore(context).save(new JSONArray().put(profile));
        MainActivity browser=(MainActivity)getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(1200);
        java.lang.reflect.Field thumbs=MainActivity.class.getDeclaredField("thumbnails"),rows=MainActivity.class.getDeclaredField("entries"),query=MainActivity.class.getDeclaredField("query");thumbs.setAccessible(true);rows.setAccessible(true);query.setAccessible(true);
        java.lang.reflect.Method setting=MainActivity.class.getDeclaredMethod("setPreviewCount",int.class),render=MainActivity.class.getDeclaredMethod("render");setting.setAccessible(true);render.setAccessible(true);
        getInstrumentation().runOnMainSync(()->{try{query.set(browser,"sample.mp4");render.invoke(browser);}catch(Exception error){throw new RuntimeException(error);}});
        JSONObject entry=null;JSONArray listed=(JSONArray)rows.get(browser);for(int i=0;i<listed.length();i++)if(listed.getJSONObject(i).optString("name").equals("sample.mp4"))entry=listed.getJSONObject(i);assertNotNull(entry);
        java.util.Set<String> keys=new java.util.HashSet<>();
        for(int count:VideoThumbnails.FRAME_COUNTS){getInstrumentation().runOnMainSync(()->{try{setting.invoke(browser,count);}catch(Exception error){throw new RuntimeException(error);}});VideoThumbnails manager=(VideoThumbnails)thumbs.get(browser);assertEquals(count,manager.frameCount());assertEquals(count,context.getSharedPreferences("MainActivity",0).getInt("previewFrames",0));assertTrue(keys.add(manager.key(profile,entry)));if(count>0)assertEquals(.5f/count,VideoThumbnails.fraction(0,count),.0001f);}
        for(int count:new int[]{1,20}){
            getInstrumentation().runOnMainSync(()->{try{setting.invoke(browser,count);}catch(Exception error){throw new RuntimeException(error);}});
            VideoThumbnails manager=(VideoThumbnails)thumbs.get(browser);android.graphics.Bitmap[] images=null;long deadline=android.os.SystemClock.elapsedRealtime()+90000;
            while(images==null&&android.os.SystemClock.elapsedRealtime()<deadline){images=manager.get(profile,entry);Thread.sleep(150);}assertNotNull("Missing "+count+" preview frames",images);assertEquals(count,images.length);
        }
        getInstrumentation().runOnMainSync(browser::finish);
        MainActivity again=(MainActivity)getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));assertEquals(20,((VideoThumbnails)thumbs.get(again)).frameCount());getInstrumentation().runOnMainSync(again::finish);
    }
    @Test public void testLargeFolderRenderBudget() throws Exception {
        Context context=getInstrumentation().getTargetContext();new AccountStore(context).save(new JSONArray());
        MainActivity browser=(MainActivity)getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        JSONArray files=new JSONArray();for(int i=0;i<3000;i++){int n=(i*7919)%3000;files.put(new JSONObject().put("name","Video "+n+" sample.mkv").put("path","/large/"+n+".mkv").put("size",1000+n).put("metadataKnown",true).put("sizeExact",true));}
        java.lang.reflect.Field entries=MainActivity.class.getDeclaredField("entries");entries.setAccessible(true);java.lang.reflect.Method render=MainActivity.class.getDeclaredMethod("render");render.setAccessible(true);
        long[] timing=new long[3];for(int i=0;i<timing.length;i++){final int index=i;getInstrumentation().runOnMainSync(()->{try{entries.set(browser,files);long start=android.os.SystemClock.elapsedRealtime();render.invoke(browser);timing[index]=android.os.SystemClock.elapsedRealtime()-start;}catch(Exception error){throw new RuntimeException(error);}});}
        java.lang.reflect.Field visible=MainActivity.class.getDeclaredField("visible");visible.setAccessible(true);long ready=android.os.SystemClock.elapsedRealtime()+5000;while(((java.util.List<?>)visible.get(browser)).size()!=3000&&android.os.SystemClock.elapsedRealtime()<ready)Thread.sleep(50);assertEquals(3000,((java.util.List<?>)visible.get(browser)).size());
        getInstrumentation().runOnMainSync(browser::finish);System.out.println("Large folder render ms="+java.util.Arrays.toString(timing));
        android.os.Bundle metrics=new android.os.Bundle();metrics.putString("large_folder_render_ms",java.util.Arrays.toString(timing));getInstrumentation().sendStatus(2,metrics);
        long worst=0;for(long value:timing)worst=Math.max(worst,value);assertTrue("3000-file main-thread render took "+worst+" ms",worst<300);
    }
    @Test public void testDefaultSingleAndDisabledPersist() throws Exception {
        Context context=getInstrumentation().getTargetContext();new AccountStore(context).save(new JSONArray());context.getSharedPreferences("MainActivity",0).edit().remove("previewFrames").commit();
        MainActivity browser=(MainActivity)getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));java.lang.reflect.Field thumbs=MainActivity.class.getDeclaredField("thumbnails");thumbs.setAccessible(true);assertEquals(1,((VideoThumbnails)thumbs.get(browser)).frameCount());
        java.lang.reflect.Method setting=MainActivity.class.getDeclaredMethod("setPreviewCount",int.class);setting.setAccessible(true);getInstrumentation().runOnMainSync(()->{try{setting.invoke(browser,0);}catch(Exception error){throw new RuntimeException(error);}});getInstrumentation().runOnMainSync(browser::finish);
        MainActivity again=(MainActivity)getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));assertEquals(0,((VideoThumbnails)thumbs.get(again)).frameCount());getInstrumentation().runOnMainSync(again::finish);
    }
    @Test public void testPreviewTransferComparisonAndDisabled() throws Exception {
        Context context=getInstrumentation().getTargetContext();new AccountStore(context).save(new JSONArray());
        MainActivity browser=(MainActivity)getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        java.util.concurrent.atomic.AtomicReference<VideoThumbnails> holder=new java.util.concurrent.atomic.AtomicReference<>();getInstrumentation().runOnMainSync(()->holder.set(new VideoThumbnails(browser,new android.view.TextureView(browser),()->{})));VideoThumbnails manager=holder.get();
        JSONObject profile=account("http"),entry=null;JSONArray files=new RemoteClient(profile).list("/");for(int i=0;i<files.length();i++)if(files.getJSONObject(i).optString("name").equals("bitrate-8mbps.mp4"))entry=new RemoteClient(profile).stat(files.getJSONObject(i));assertNotNull(entry);
        final JSONObject source=new JSONObject(entry.toString()).put("modified",System.currentTimeMillis());long[] bytes=new long[2];int slot=0;
        for(int count:new int[]{1,10}){getInstrumentation().runOnMainSync(()->{manager.setFrameCount(count);manager.request(profile,source);});long deadline=android.os.SystemClock.elapsedRealtime()+90000;android.graphics.Bitmap[] frames=null;while(frames==null&&android.os.SystemClock.elapsedRealtime()<deadline){frames=manager.get(profile,source);Thread.sleep(150);}assertNotNull(frames);assertEquals(count,frames.length);bytes[slot++]=manager.lastTransferBytes;}
        getInstrumentation().runOnMainSync(()->{manager.setFrameCount(0);manager.request(profile,source);});Thread.sleep(300);assertNull(manager.get(profile,source));java.lang.reflect.Field pending=VideoThumbnails.class.getDeclaredField("pending");pending.setAccessible(true);assertTrue(((java.util.Set<?>)pending.get(manager)).isEmpty());
        System.out.println("Preview transfer 1="+bytes[0]+", 10="+bytes[1]+", saving="+(100*(1-bytes[0]/(double)bytes[1]))+"%");assertTrue(bytes[0]>0&&bytes[0]<bytes[1]);
        getInstrumentation().runOnMainSync(()->{manager.close();browser.finish();});
    }
    @Test public void testAuthenticatedFtpDownload() throws Exception {
        Context context=getInstrumentation().getTargetContext();JSONObject profile=account("ftp");new AccountStore(context).save(new JSONArray().put(profile));
        Activity browser=getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(900);
        RemoteClient client=new RemoteClient(profile);JSONArray rows=client.list("/");JSONObject entry=null;for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getString("name").equals("sample.mp4"))entry=rows.getJSONObject(i);assertNotNull(entry);
        File directory=new File(context.getFilesDir(),"downloads");directory.mkdirs();File destination=new File(directory,"download-test.mp4");
        Intent service=new Intent(context,DownloadService.class).putExtra("account",profile.getString("id")).putExtra("entry",entry.toString()).putExtra("destination",android.net.Uri.fromFile(destination).toString());
        getInstrumentation().runOnMainSync(()->{if(android.os.Build.VERSION.SDK_INT>=26)context.startForegroundService(service);else context.startService(service);});
        long deadline=android.os.SystemClock.elapsedRealtime()+15000;boolean done=false;
        while(android.os.SystemClock.elapsedRealtime()<deadline){JSONArray history=new JSONArray(context.getSharedPreferences("downloads",0).getString("history","[]"));if(history.length()>0&&history.getJSONObject(0).optString("name").equals("sample.mp4")&&history.getJSONObject(0).optString("result").equals("Zapisano plik")){done=true;break;}Thread.sleep(150);}
        assertTrue("Download did not complete",done);assertEquals(client.size(entry),destination.length());destination.delete();getInstrumentation().runOnMainSync(browser::finish);
    }
    private android.app.Notification downloadNotification(int id){
        android.app.NotificationManager manager=(android.app.NotificationManager)getInstrumentation().getTargetContext().getSystemService(Context.NOTIFICATION_SERVICE);for(android.service.notification.StatusBarNotification n:manager.getActiveNotifications())if(n.getId()==id)return n.getNotification();return null;
    }
    private JSONObject waitDownload(String name) throws Exception {
        long deadline=android.os.SystemClock.elapsedRealtime()+20000;
        while(android.os.SystemClock.elapsedRealtime()<deadline){JSONArray history=new JSONArray(getInstrumentation().getTargetContext().getSharedPreferences("downloads",0).getString("history","[]"));for(int i=0;i<history.length();i++)if(name.equals(history.getJSONObject(i).optString("name")))return history.getJSONObject(i);Thread.sleep(100);}fail("Download did not finish: "+name);return null;
    }
    private void startTestDownload(JSONObject profile,JSONObject entry,android.net.Uri target){
        Context context=getInstrumentation().getTargetContext();Intent service=new Intent(context,DownloadService.class).putExtra("account",profile.optString("id")).putExtra("entry",entry.toString()).putExtra("destination",target.toString());getInstrumentation().runOnMainSync(()->context.startForegroundService(service));
    }
    private File downloadTarget(String name){File directory=new File(getInstrumentation().getTargetContext().getFilesDir(),"downloads");directory.mkdirs();return new File(directory,name);}
    @Test public void testDownloadProgressCompletionAndNotificationOpen() throws Exception {
        Context context=getInstrumentation().getTargetContext();JSONObject profile=account("http");new AccountStore(context).save(new JSONArray().put(profile));Activity browser=getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        String name="notification-progress.mp4";JSONObject entry=new JSONObject().put("name",name).put("path","/slow/download-progress.bin");File file=downloadTarget(name);android.net.Uri target=androidx.core.content.FileProvider.getUriForFile(context,context.getPackageName()+".downloads",file);startTestDownload(profile,entry,target);
        long deadline=android.os.SystemClock.elapsedRealtime()+12000;android.app.Notification running=null;
        while(android.os.SystemClock.elapsedRealtime()<deadline){android.app.Notification n=downloadNotification(DownloadService.FOREGROUND_ID);if(n!=null&&n.extras.getInt(android.app.Notification.EXTRA_PROGRESS)>0&&n.extras.getInt(android.app.Notification.EXTRA_PROGRESS)<100){running=n;break;}Thread.sleep(100);}
        assertNotNull("Missing determinate progress notification",running);assertEquals(100,running.extras.getInt(android.app.Notification.EXTRA_PROGRESS_MAX));assertFalse(running.extras.getBoolean(android.app.Notification.EXTRA_PROGRESS_INDETERMINATE));assertTrue(running.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString().contains("%"));assertTrue((running.flags&android.app.Notification.FLAG_ONGOING_EVENT)!=0);
        JSONObject done=waitDownload(name);assertEquals("Zapisano plik",done.getString("result"));assertEquals(2097152,file.length());android.app.Notification completed=null;deadline=android.os.SystemClock.elapsedRealtime()+5000;
        while(android.os.SystemClock.elapsedRealtime()<deadline){completed=downloadNotification(done.getInt("notificationId"));if(completed!=null&&downloadNotification(DownloadService.FOREGROUND_ID)==null)break;Thread.sleep(100);}assertNotNull(completed);assertNull(downloadNotification(DownloadService.FOREGROUND_ID));assertEquals(android.R.drawable.stat_sys_download_done,completed.getSmallIcon().getResId());assertEquals(0,completed.extras.getInt(android.app.Notification.EXTRA_PROGRESS_MAX));assertEquals(0,completed.flags&android.app.Notification.FLAG_ONGOING_EVENT);assertTrue((completed.flags&android.app.Notification.FLAG_AUTO_CANCEL)!=0);
        AtomicReference<Intent> opened=new AtomicReference<>();android.app.Instrumentation.ActivityMonitor monitor=new android.app.Instrumentation.ActivityMonitor(){@Override public android.app.Instrumentation.ActivityResult onStartActivity(Intent intent){if(Intent.ACTION_VIEW.equals(intent.getAction())){opened.set(new Intent(intent));return new android.app.Instrumentation.ActivityResult(Activity.RESULT_OK,null);}return null;}};getInstrumentation().addMonitor(monitor);
        try{completed.contentIntent.send();deadline=android.os.SystemClock.elapsedRealtime()+5000;while(opened.get()==null&&android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);assertNotNull("Notification did not open the downloaded file",opened.get());assertEquals(target,opened.get().getData());assertEquals("video/mp4",opened.get().getType());assertTrue((opened.get().getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0);assertEquals(target,opened.get().getClipData().getItemAt(0).getUri());}finally{getInstrumentation().removeMonitor(monitor);file.delete();getInstrumentation().runOnMainSync(browser::finish);}
    }
    @Test public void testDownloadCancelDoesNotCancelNextFile() throws Exception {
        Context context=getInstrumentation().getTargetContext();JSONObject profile=account("http");new AccountStore(context).save(new JSONArray().put(profile));Activity browser=getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        String first="cancelled-test.bin",second="queued-test.mp4";File a=downloadTarget(first),b=downloadTarget(second);startTestDownload(profile,new JSONObject().put("name",first).put("path","/slow/download-progress.bin"),android.net.Uri.fromFile(a));
        long deadline=android.os.SystemClock.elapsedRealtime()+10000;android.app.Notification ongoing=null;while(android.os.SystemClock.elapsedRealtime()<deadline){ongoing=downloadNotification(DownloadService.FOREGROUND_ID);if(ongoing!=null&&ongoing.extras.getInt(android.app.Notification.EXTRA_PROGRESS)>0)break;Thread.sleep(100);}assertNotNull(ongoing);assertNotNull(ongoing.actions);
        startTestDownload(profile,new JSONObject().put("name",second).put("path","/sample.mp4"),android.net.Uri.fromFile(b));ongoing.actions[0].actionIntent.send();JSONObject cancelled=waitDownload(first),finished=waitDownload(second);assertTrue(cancelled.getString("result").startsWith("Anulowano"));assertEquals("Zapisano plik",finished.getString("result"));assertNotEquals(cancelled.getInt("notificationId"),finished.getInt("notificationId"));assertTrue(a.length()<2097152);assertEquals(new RemoteClient(profile).size(new JSONObject().put("path","/sample.mp4")),b.length());
        android.app.Notification error=downloadNotification(cancelled.getInt("notificationId"));assertNotNull(error);assertEquals(android.R.drawable.stat_notify_error,error.getSmallIcon().getResId());assertEquals(0,error.extras.getInt(android.app.Notification.EXTRA_PROGRESS_MAX));a.delete();b.delete();getInstrumentation().runOnMainSync(browser::finish);
    }
    @Test public void testTruncatedDownloadIsNotReportedAsSaved() throws Exception {
        Context context=getInstrumentation().getTargetContext();JSONObject profile=account("http");new AccountStore(context).save(new JSONArray().put(profile));Activity browser=getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));String name="truncated-test.bin";File file=downloadTarget(name);startTestDownload(profile,new JSONObject().put("name",name).put("path","/truncated/download-progress.bin"),android.net.Uri.fromFile(file));JSONObject failed=waitDownload(name);assertTrue(failed.getString("result").startsWith("Błąd"));assertTrue(file.length()<2097152);file.delete();getInstrumentation().runOnMainSync(browser::finish);
    }
    @Test public void testDownloadedFileProviderAndReadOnlyIntent() throws Exception {
        Context context=getInstrumentation().getTargetContext();File file=downloadTarget("local-film.MOV");try(FileOutputStream output=new FileOutputStream(file)){output.write(new byte[]{1,2,3});}
        Intent view=OpenDownloadActivity.viewIntent(context,android.net.Uri.fromFile(file),file.getName());assertEquals(Intent.ACTION_VIEW,view.getAction());assertEquals("content",view.getData().getScheme());assertEquals("video/quicktime",view.getType());assertTrue((view.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0);assertEquals(0,view.getFlags()&Intent.FLAG_GRANT_WRITE_URI_PERMISSION);try(InputStream input=context.getContentResolver().openInputStream(view.getData())){assertEquals(1,input.read());}
        try{OpenDownloadActivity.viewIntent(context,android.net.Uri.fromFile(new File(context.getFilesDir(),"private-settings.json")),"settings.json");fail("Provider exposed files outside downloads");}catch(IllegalArgumentException expected){}file.delete();
    }
    @Test public void testLegacyCompletedNotificationIsRepaired() throws Exception {
        Context context=getInstrumentation().getTargetContext();String name="legacy-completed.mp4";File file=downloadTarget(name);try(FileOutputStream output=new FileOutputStream(file)){output.write(1);}long now=System.currentTimeMillis();
        JSONArray history=new JSONArray().put(new JSONObject().put("name",name).put("uri",android.net.Uri.fromFile(file).toString()).put("result","Zapisano plik").put("at",now));context.getSharedPreferences("downloads",0).edit().putString("history",history.toString()).commit();
        android.app.Notification old=new android.app.Notification.Builder(context,DownloadService.CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download).setWhen(now).setContentTitle(name).setContentText("Zapisano plik").build();android.app.NotificationManager manager=(android.app.NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);manager.notify(900001,old);
        DownloadService.repairCompletedNotifications(context);android.app.Notification repaired=downloadNotification(900001);assertNotNull(repaired);assertEquals(android.R.drawable.stat_sys_download_done,repaired.getSmallIcon().getResId());assertTrue((repaired.flags&android.app.Notification.FLAG_AUTO_CANCEL)!=0);assertNotNull(repaired.contentIntent);file.delete();
    }
    @Test public void testMissingStartupFolderAndFavorite() throws Exception {
        Context context=getInstrumentation().getTargetContext();JSONObject profile=account("http");new AccountStore(context).save(new JSONArray().put(profile));
        LibraryStore library=new LibraryStore(context);library.start(profile,"/deleted-folder");
        MainActivity browser=(MainActivity)getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        java.lang.reflect.Field path=MainActivity.class.getDeclaredField("path"), rows=MainActivity.class.getDeclaredField("entries"),pending=MainActivity.class.getDeclaredField("pendingOpen");path.setAccessible(true);rows.setAccessible(true);pending.setAccessible(true);
        long deadline=android.os.SystemClock.elapsedRealtime()+12000;
        while(android.os.SystemClock.elapsedRealtime()<deadline&&(!"/".equals(path.get(browser))||((JSONArray)rows.get(browser)).length()==0))Thread.sleep(150);
        assertEquals("/",path.get(browser));assertTrue(((JSONArray)rows.get(browser)).length()>0);
        getInstrumentation().runOnMainSync(()->{try{pending.set(browser,"/missing-file.mov");browser.navigate("/");}catch(Exception error){throw new RuntimeException(error);}});
        deadline=android.os.SystemClock.elapsedRealtime()+10000;while(android.os.SystemClock.elapsedRealtime()<deadline&&!"".equals(pending.get(browser)))Thread.sleep(150);
        assertEquals("",pending.get(browser));assertFalse(browser.isFinishing());library.start(profile,"");getInstrumentation().runOnMainSync(browser::finish);
    }
    @Test public void testFullWidthCardsKeepAspectRatio() throws Exception {
        Context context=getInstrumentation().getTargetContext();JSONObject profile=account("http");new AccountStore(context).save(new JSONArray().put(profile));
        context.getSharedPreferences("MainActivity",0).edit().putString("sort","name").putBoolean("descending",false).putInt("previewFrames",10).commit();
        MainActivity browser=(MainActivity)getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(1200);
        java.lang.reflect.Field cards=MainActivity.class.getDeclaredField("cards"),type=MainActivity.class.getDeclaredField("kind"),listField=MainActivity.class.getDeclaredField("items");cards.setAccessible(true);type.setAccessible(true);listField.setAccessible(true);
        java.lang.reflect.Method render=MainActivity.class.getDeclaredMethod("render");render.setAccessible(true);
        getInstrumentation().runOnMainSync(()->{try{cards.setBoolean(browser,true);type.set(browser,"video");render.invoke(browser);java.lang.reflect.Field rows=MainActivity.class.getDeclaredField("visible");rows.setAccessible(true);java.util.List<JSONObject> files=(java.util.List<JSONObject>)rows.get(browser);for(int i=0;i<files.size();i++)if(MainActivity.kind(files.get(i)).equals("video")){((android.widget.ListView)listField.get(browser)).setSelection(i);break;}}catch(Exception error){throw new RuntimeException(error);}});Thread.sleep(700);
        android.widget.ListView list=(android.widget.ListView)listField.get(browser);assertTrue(list.getChildCount()>0);
        android.view.ViewGroup row=(android.view.ViewGroup)list.getChildAt(0);android.view.View artwork=row.getChildAt(0);
        assertTrue("Card does not fill list width",artwork.getWidth()>=list.getWidth()-64);
        assertTrue("Card preview is not 16:9",Math.abs(artwork.getHeight()/(float)artwork.getWidth()-9f/16f)<.06f);
        getInstrumentation().runOnMainSync(browser::finish);
    }

}
