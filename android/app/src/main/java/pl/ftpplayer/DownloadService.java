package pl.ftpplayer;

import android.app.*;
import android.os.*;
import android.content.*;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Serial authenticated downloads with distinct progress, completion and open-file actions. */
public final class DownloadService extends Service {
    static final String CHANNEL="ftp_downloads";
    static final int FOREGROUND_ID=17;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ConcurrentHashMap<Integer,Job> jobs=new ConcurrentHashMap<>();
    private volatile InputStream active;
    private volatile Job current;
    private volatile boolean destroyed;
    private int pending;
    private NotificationManager manager;
    private static final class Job {
        final int id;final String account,json,destination;final AtomicBoolean cancel=new AtomicBoolean();
        Job(int id,Intent intent){this.id=id;account=intent.getStringExtra("account");json=intent.getStringExtra("entry");destination=intent.getStringExtra("destination");}
    }
    @Override public void onCreate(){super.onCreate();manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(new NotificationChannel(CHANNEL,"Pobieranie plików",NotificationManager.IMPORTANCE_LOW));}
    private static Notification buildNotification(Context context,String title,String text,int percent,boolean ongoing,boolean success,String destination,int id){
        Notification.Builder builder=Build.VERSION.SDK_INT>=26?new Notification.Builder(context,CHANNEL):new Notification.Builder(context);
        builder.setSmallIcon(ongoing?android.R.drawable.stat_sys_download:success?android.R.drawable.stat_sys_download_done:android.R.drawable.stat_notify_error)
            .setContentTitle(title).setContentText(text).setOngoing(ongoing)
            .setAutoCancel(!ongoing).setOnlyAlertOnce(true).setCategory(ongoing?Notification.CATEGORY_PROGRESS:Notification.CATEGORY_STATUS);
        if(!ongoing)builder.setStyle(new Notification.BigTextStyle().bigText(text));
        builder.setShowWhen(!ongoing);
        builder.setProgress(ongoing?100:0,ongoing?Math.max(0,percent):0,ongoing&&percent<0);
        if(ongoing){Intent stop=new Intent(context,DownloadService.class).setAction("cancel").putExtra("job",id);builder.addAction(android.R.drawable.ic_delete,"Anuluj",PendingIntent.getService(context,id,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));}
        Intent open=success?new Intent(context,OpenDownloadActivity.class).setData(Uri.parse(destination)).putExtra("name",title):new Intent(context,MainActivity.class);
        builder.setContentIntent(PendingIntent.getActivity(context,id,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));return builder.build();
    }
    private Notification notification(String title,String text,int percent,boolean ongoing,boolean success,String destination,int id){return buildNotification(this,title,text,percent,ongoing,success,destination,id);}
    static void repairCompletedNotifications(Context context){
        try{
            NotificationManager manager=(NotificationManager)context.getSystemService(NOTIFICATION_SERVICE);JSONArray history=new JSONArray(context.getSharedPreferences("downloads",0).getString("history","[]"));
            for(android.service.notification.StatusBarNotification item:manager.getActiveNotifications()){
                Notification old=item.getNotification();if(item.getId()==FOREGROUND_ID||(old.flags&Notification.FLAG_ONGOING_EVENT)!=0||old.getSmallIcon()==null||old.getSmallIcon().getResId()!=android.R.drawable.stat_sys_download)continue;
                if(Build.VERSION.SDK_INT>=26&&!CHANNEL.equals(old.getChannelId()))continue;
                String name=old.extras.getCharSequence(Notification.EXTRA_TITLE,"").toString();JSONObject saved=null;long closest=Long.MAX_VALUE;
                for(int i=0;i<history.length();i++){JSONObject row=history.optJSONObject(i);if(row==null||!name.equals(row.optString("name")))continue;long distance=Math.abs(old.when-row.optLong("at"));if(row.optInt("notificationId",-1)==item.getId()){saved=row;break;}if(distance<closest){closest=distance;saved=row;}}
                if(saved==null||saved.optString("uri").isEmpty())continue;boolean success=saved.optString("result").equals("Zapisano plik");String text=saved.optString("result")+(success?" · Dotknij, aby otworzyć":"");
                manager.notify(item.getId(),buildNotification(context,name,text,100,false,success,saved.optString("uri"),item.getId()));
            }
        }catch(Exception ignored){}
    }
    private void progress(Job job,String name,long copied,long size){
        int percent=size>0?(int)Math.min(100,100.0*copied/size):-1;
        String text=(percent>=0?percent+"% · ":"")+android.text.format.Formatter.formatShortFileSize(this,copied)+" / "+(size>0?android.text.format.Formatter.formatShortFileSize(this,size):"rozmiar nieznany");
        manager.notify(FOREGROUND_ID,notification(name,text,percent,true,false,null,job.id));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null)return START_NOT_STICKY;
        if("cancel".equals(intent.getAction())){
            Job job=jobs.get(intent.getIntExtra("job",current==null?-1:current.id));if(job!=null){job.cancel.set(true);if(job==current)closeActive();}return START_NOT_STICKY;
        }
        android.content.SharedPreferences prefs=getSharedPreferences("downloads",0);int id=prefs.getInt("nextNotification",1000);prefs.edit().putInt("nextNotification",id==Integer.MAX_VALUE?1000:id+1).apply();
        Job job=new Job(id,intent);jobs.put(id,job);pending++;
        if(pending==1)startForeground(FOREGROUND_ID,notification("FTP Player","Przygotowanie pobierania…",-1,true,false,null,id));
        worker.execute(()->download(job));return START_NOT_STICKY;
    }
    private void download(Job job){
        current=job;String name="Plik",result="Nie udało się pobrać pliku";long copied=0;boolean success=false;
        try{
            if(job.cancel.get())throw new InterruptedIOException();
            JSONObject entry=new JSONObject(job.json),account=null;name=entry.getString("name");
            JSONArray accounts=new AccountStore(this).load();for(int i=0;i<accounts.length();i++)if(accounts.getJSONObject(i).optString("id").equals(job.account))account=accounts.getJSONObject(i);
            if(account==null)throw new IOException("Brak konta");
            RemoteClient client=new RemoteClient(account);long size=client.size(entry),last=SystemClock.elapsedRealtime();progress(job,name,0,size);
            try(InputStream input=client.read(entry,0);OutputStream output=getContentResolver().openOutputStream(Uri.parse(job.destination),"wt")){
                if(output==null)throw new IOException("Brak miejsca zapisu");active=input;byte[] buffer=new byte[262144];int count;
                while((count=input.read(buffer))!=-1){if(job.cancel.get())throw new InterruptedIOException();output.write(buffer,0,count);copied+=count;
                    if(SystemClock.elapsedRealtime()-last>=500){last=SystemClock.elapsedRealtime();progress(job,name,copied,size);}
                }
                if(job.cancel.get())throw new InterruptedIOException();if(size>0&&copied!=size)throw new EOFException();output.flush();
            }finally{active=null;}
            success=true;result="Zapisano plik";
        }catch(Exception error){result=job.cancel.get()?"Anulowano — plik może być niekompletny":"Błąd pobierania — sprawdź połączenie i miejsce zapisu";}
        if(destroyed)return;
        try{
            android.content.SharedPreferences prefs=getSharedPreferences("downloads",0);JSONArray old=new JSONArray(prefs.getString("history","[]")),rows=new JSONArray();
            rows.put(new JSONObject().put("name",name).put("result",result).put("bytes",copied).put("uri",job.destination).put("notificationId",job.id).put("at",System.currentTimeMillis()));for(int i=0;i<Math.min(49,old.length());i++)rows.put(old.get(i));prefs.edit().putString("history",rows.toString()).apply();
        }catch(Exception ignored){}
        String text=success?result+" · "+android.text.format.Formatter.formatShortFileSize(this,copied)+" · Dotknij, aby otworzyć":result;
        manager.notify(job.id,notification(name,text,100,false,success,job.destination,job.id));jobs.remove(job.id);current=null;
        new Handler(Looper.getMainLooper()).post(()->{pending--;if(pending==0){stopForeground(true);stopSelf();}});
    }
    private void closeActive(){InputStream stream=active;if(stream!=null)new Thread(()->{try{stream.close();}catch(Exception ignored){}},"download-cancel").start();}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){destroyed=true;for(Job job:jobs.values())job.cancel.set(true);closeActive();worker.shutdownNow();super.onDestroy();}
}
