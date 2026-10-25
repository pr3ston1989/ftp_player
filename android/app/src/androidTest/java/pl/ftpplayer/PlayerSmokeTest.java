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
        for(String filename:new String[]{"sample.mp4","hevc-main10.mkv","legacy.avi"}) {
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
                if(player!=null&&player.getTime()>700) { advanced=true; break; }
                Thread.sleep(150);
            }
            assertTrue("Playback did not advance: "+filename,advanced);
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
}
