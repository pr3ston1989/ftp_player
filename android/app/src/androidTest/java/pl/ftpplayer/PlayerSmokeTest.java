package pl.ftpplayer;

import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
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
            assertNotNull(selected); String url=app.server.register(account(protocol),selected);
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
        for(String filename:new String[]{"sample.mp4","hevc-main10.mkv","legacy.avi","mpeg2.mpg","windows-media.wmv","xvid.avi","divx.avi","realvideo.rm"}) {
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
}
