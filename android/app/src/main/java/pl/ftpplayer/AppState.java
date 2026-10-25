package pl.ftpplayer;
import android.app.Application;
import java.util.concurrent.*;

/** Keep the loopback bridge alive while browsing or playing media. */
public final class AppState extends Application {
    MediaServer server;
    final ExecutorService workers=Executors.newFixedThreadPool(4);
    @Override public void onCreate() {
        super.onCreate();
        try { server=new MediaServer(); server.start(45000,false); }
        catch(Exception error) { throw new IllegalStateException("Nie można uruchomić odtwarzacza",error); }
    }
}
