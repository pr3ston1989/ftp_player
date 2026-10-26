package pl.ftpplayer;

import android.app.*;
import android.os.*;
import android.content.*;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** User-selected document downloads survive browser activity changes and report errors honestly. */
public final class DownloadService extends Service {
    private static final String CHANNEL="ftp_downloads";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancel=new AtomicBoolean();
    private volatile InputStream active;
    private int pending;
    private NotificationManager manager;
    @Override public void onCreate(){super.onCreate();manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(new NotificationChannel(CHANNEL,"Pobieranie plików",NotificationManager.IMPORTANCE_LOW));}
    private Notification notification(String title,String text,int percent,boolean ongoing){
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(title).setContentText(text).setOngoing(ongoing).setOnlyAlertOnce(true);
        if(ongoing){b.setProgress(100,Math.max(0,percent),percent<0);Intent stop=new Intent(this,DownloadService.class).setAction("cancel");b.addAction(android.R.drawable.ic_delete,"Anuluj",PendingIntent.getService(this,0,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));}
        Intent open=new Intent(this,MainActivity.class);b.setContentIntent(PendingIntent.getActivity(this,1,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));return b.build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null)return START_NOT_STICKY;
        if("cancel".equals(intent.getAction())){cancel.set(true);InputStream stream=active;if(stream!=null)new Thread(()->{try{stream.close();}catch(Exception ignored){}}).start();return START_NOT_STICKY;}
        pending++;startForeground(17,notification("FTP Player","Przygotowanie pobierania…",-1,true));
        String id=intent.getStringExtra("account"), json=intent.getStringExtra("entry"), destination=intent.getStringExtra("destination");
        worker.execute(()->download(id,json,destination,startId));return START_NOT_STICKY;
    }
    private void download(String accountId,String json,String destination,int startId){
        String name="Plik", result="Nie udało się pobrać pliku";long copied=0;cancel.set(false);
        try {
            JSONObject entry=new JSONObject(json),account=null;name=entry.getString("name");
            JSONArray accounts=new AccountStore(this).load();for(int i=0;i<accounts.length();i++)if(accounts.getJSONObject(i).optString("id").equals(accountId))account=accounts.getJSONObject(i);
            if(account==null)throw new IOException("Brak konta");
            RemoteClient client=new RemoteClient(account);long size=client.size(entry),last=0;
            try(InputStream input=client.read(entry,0);OutputStream output=getContentResolver().openOutputStream(Uri.parse(destination),"wt")){
                if(output==null)throw new IOException("Brak miejsca zapisu");active=input;byte[] buffer=new byte[262144];int count;
                while((count=input.read(buffer))!=-1){if(cancel.get())throw new InterruptedIOException();output.write(buffer,0,count);copied+=count;
                    if(SystemClock.elapsedRealtime()-last>600){last=SystemClock.elapsedRealtime();manager.notify(17,notification(name,android.text.format.Formatter.formatShortFileSize(this,copied)+" / "+(size>0?android.text.format.Formatter.formatShortFileSize(this,size):"?"),size>0?(int)Math.min(100,copied*100/size):-1,true));}
                }
                if(size>0&&copied!=size)throw new EOFException();output.flush();
            } finally {active=null;}
            result="Zapisano plik";
        } catch(Exception error){result=cancel.get()?"Anulowano — plik może być niekompletny":"Błąd pobierania — sprawdź połączenie i miejsce zapisu";}
        try {
            android.content.SharedPreferences prefs=getSharedPreferences("downloads",0);JSONArray old=new JSONArray(prefs.getString("history","[]")), rows=new JSONArray();
            rows.put(new JSONObject().put("name",name).put("result",result).put("bytes",copied).put("uri",destination).put("at",System.currentTimeMillis()));for(int i=0;i<Math.min(49,old.length());i++)rows.put(old.get(i));prefs.edit().putString("history",rows.toString()).apply();
        } catch(Exception ignored){}
        manager.notify((int)(System.currentTimeMillis()%100000)+100,notification(name,result,100,false));
        new Handler(Looper.getMainLooper()).post(()->{pending--;if(pending==0){stopForeground(true);stopSelf();}});
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){cancel.set(true);worker.shutdownNow();super.onDestroy();}
}
