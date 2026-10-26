package pl.ftpplayer;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.MimeTypeMap;
import android.widget.Toast;
import java.io.File;
import java.util.Locale;

/** Delegate a completed local download to the user's default viewer with a read-only grant. */
public final class OpenDownloadActivity extends Activity {
    static Intent viewIntent(Context context,Uri uri,String name){
        if("file".equals(uri.getScheme()))uri=androidx.core.content.FileProvider.getUriForFile(context,context.getPackageName()+".downloads",new File(uri.getPath()));
        if(!"content".equals(uri.getScheme()))throw new IllegalArgumentException("Nieprawidłowe miejsce zapisu");
        int dot=name.lastIndexOf('.');String type=dot<0?null:MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot+1).toLowerCase(Locale.ROOT));
        if(type==null)type=context.getContentResolver().getType(uri);if(type==null)type="application/octet-stream";
        Intent view=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        view.setClipData(ClipData.newRawUri(name,uri));return view;
    }
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        try{Uri uri=getIntent().getData();if(uri==null)throw new IllegalArgumentException();startActivity(viewIntent(this,uri,getIntent().getStringExtra("name")==null?"plik":getIntent().getStringExtra("name")));}
        catch(ActivityNotFoundException error){Toast.makeText(this,"Brak aplikacji obsługującej ten typ pliku",Toast.LENGTH_LONG).show();}
        catch(Exception error){Toast.makeText(this,"Nie można otworzyć pliku. Sprawdź, czy nadal istnieje i jest dostępny.",Toast.LENGTH_LONG).show();}
        finally{finish();}
    }
}
