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
    private java.util.Map<String,?> originalBrowser;
    @Before public void preserveProfiles() {
        originalBrowser=getInstrumentation().getTargetContext().getSharedPreferences("MainActivity",0).getAll();
        originalDownloads=getInstrumentation().getTargetContext().getSharedPreferences("downloads",0).getString("history","[]");
        originalProfiles=getInstrumentation().getTargetContext().getSharedPreferences("accounts",0).getString("profiles","[]");
    }
    @After public void restoreProfiles() {
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
    @Test public void testThreeCachedVideoFrames() throws Exception {
        Context context=getInstrumentation().getTargetContext();JSONObject profile=account("http");new AccountStore(context).save(new JSONArray().put(profile));
        MainActivity browser=(MainActivity)getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        java.lang.reflect.Field rowsField=MainActivity.class.getDeclaredField("entries"), thumbsField=MainActivity.class.getDeclaredField("thumbnails");rowsField.setAccessible(true);thumbsField.setAccessible(true);
        long deadline=android.os.SystemClock.elapsedRealtime()+60000;android.graphics.Bitmap[] frames=null;JSONObject selected=null;boolean scrolled=false;
        while(android.os.SystemClock.elapsedRealtime()<deadline&&frames==null){
            JSONArray rows=(JSONArray)rowsField.get(browser);
            for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(MainActivity.kind(row).equals("video")){selected=row;break;}}
            if(selected!=null){if(!scrolled){showFile(browser,selected.optString("path"));scrolled=true;}frames=((VideoThumbnails)thumbsField.get(browser)).get(profile,selected);}
            Thread.sleep(200);
        }
        assertNotNull("No thumbnails: "+(selected==null?"no selected video":selected.toString())+"; "+((VideoThumbnails)thumbsField.get(browser)).debug(),frames);assertEquals(3,frames.length);
        java.util.Set<Integer> colors=new java.util.HashSet<>();for(int x=0;x<320;x+=10)for(int y=0;y<180;y+=10)colors.add(frames[0].getPixel(x,y));assertTrue("Thumbnail is blank",colors.size()>8);
        int changedPixels=0;for(int x=0;x<320;x+=10)for(int y=0;y<180;y+=10)if(frames[0].getPixel(x,y)!=frames[2].getPixel(x,y))changedPixels++;assertTrue("Slideshow frames are identical",changedPixels>8);
        File directory=new File(context.getCacheDir(),"video-thumbnails-v1");String key=((VideoThumbnails)thumbsField.get(browser)).key(profile,selected);assertTrue(new File(directory,key+"-2.jpg").isFile());
        long cachedAt=new File(directory,key+"-2.jpg").lastModified();
        getInstrumentation().runOnMainSync(browser::finish);
        MainActivity again=(MainActivity)getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Thread.sleep(1000);showFile(again,selected.optString("path"));
        android.graphics.Bitmap[] reused=null;deadline=android.os.SystemClock.elapsedRealtime()+12000;
        while(reused==null&&android.os.SystemClock.elapsedRealtime()<deadline){reused=((VideoThumbnails)thumbsField.get(again)).get(profile,selected);Thread.sleep(150);}
        assertNotNull("Disk cache was not reused",reused);assertEquals(cachedAt,new File(directory,key+"-2.jpg").lastModified());
        JSONObject changed=new JSONObject(selected.toString()).put("modified",selected.optLong("modified")+1000);assertNotEquals(key,((VideoThumbnails)thumbsField.get(again)).key(profile,changed));
        getInstrumentation().runOnMainSync(again::finish);
    }
    @Test public void testAuthenticatedFtpDownload() throws Exception {
        Context context=getInstrumentation().getTargetContext();JSONObject profile=account("ftp");new AccountStore(context).save(new JSONArray().put(profile));
        Activity browser=getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(900);
        RemoteClient client=new RemoteClient(profile);JSONArray rows=client.list("/");JSONObject entry=null;for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getString("name").equals("sample.mp4"))entry=rows.getJSONObject(i);assertNotNull(entry);
        File destination=new File(context.getFilesDir(),"download-test.mp4");
        Intent service=new Intent(context,DownloadService.class).putExtra("account",profile.getString("id")).putExtra("entry",entry.toString()).putExtra("destination",android.net.Uri.fromFile(destination).toString());
        getInstrumentation().runOnMainSync(()->{if(android.os.Build.VERSION.SDK_INT>=26)context.startForegroundService(service);else context.startService(service);});
        long deadline=android.os.SystemClock.elapsedRealtime()+15000;boolean done=false;
        while(android.os.SystemClock.elapsedRealtime()<deadline){JSONArray history=new JSONArray(context.getSharedPreferences("downloads",0).getString("history","[]"));if(history.length()>0&&history.getJSONObject(0).optString("name").equals("sample.mp4")&&history.getJSONObject(0).optString("result").equals("Zapisano plik")){done=true;break;}Thread.sleep(150);}
        assertTrue("Download did not complete",done);assertEquals(client.size(entry),destination.length());destination.delete();getInstrumentation().runOnMainSync(browser::finish);
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
