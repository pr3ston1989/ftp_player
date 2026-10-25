package pl.ftpplayer;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;

/** Native touch and remote-friendly browser with multiple encrypted server profiles. */
public final class MainActivity extends Activity {
    static final int BG=Color.rgb(11,16,32), CARD=Color.rgb(22,32,53), TEXT=Color.rgb(232,239,249), MUTED=Color.rgb(148,163,184), ACCENT=Color.rgb(34,211,238);
    private final Handler ui=new Handler(Looper.getMainLooper());
    private AccountStore store; private JSONArray accounts=new JSONArray(), entries=new JSONArray();
    private JSONObject account; private String path="/", kind="all", query="";
    private TextView location,status; private Spinner accountPicker;
    private ListView items; private TextView empty; private final ArrayList<JSONObject> visible=new ArrayList<>(); private BaseAdapter fileAdapter; private ProgressBar progress; private int generation;
    private boolean selecting, tv, closed;
    AppState state() { return (AppState)getApplication(); }
    int dp(int n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    TextView text(String s,int size,int color) { TextView v=new TextView(this); v.setText(s); v.setTextSize(size); v.setTextColor(color); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    android.graphics.drawable.Drawable background(boolean focused) {
        GradientDrawable d=new GradientDrawable(); d.setColor(focused?Color.rgb(27,68,85):CARD); d.setCornerRadius(dp(12)); d.setStroke(dp(focused?2:1),focused?ACCENT:Color.rgb(35,49,73)); return d;
    }
    Button button(String title,Runnable action) {
        Button b=new Button(this); b.setText(title); b.setAllCaps(false); b.setTextColor(TEXT); b.setTextSize(tv?18:14);
        b.setFocusable(true); b.setBackground(background(false)); b.setMinHeight(dp(46));
        b.setOnClickListener(v->action.run()); b.setOnFocusChangeListener((v,f)->b.setBackground(background(f))); return b;
    }
    @Override public void onCreate(Bundle saved) {
        setTheme(R.style.AppTheme); super.onCreate(saved);
        tv=(getResources().getConfiguration().uiMode&Configuration.UI_MODE_TYPE_MASK)==Configuration.UI_MODE_TYPE_TELEVISION;
        store=new AccountStore(this);
        try { accounts=store.load(); } catch(Exception error) { toast("Nie udało się odczytać kont. Istniejące dane zachowano."); }
        build(); refreshAccounts();
        if(accounts.length()>0) {
            String selected=getPreferences(0).getString("account",""); int index=0;
            for(int i=0;i<accounts.length();i++) if(accounts.optJSONObject(i).optString("id").equals(selected)) index=i;
            account=accounts.optJSONObject(index); selecting=true; accountPicker.setSelection(index); selecting=false;
            path=saved==null?"/":saved.getString("path","/"); navigate(path);
        } else status.setText("Dodaj pierwszy serwer, aby otworzyć swoją bibliotekę.");
    }
    private void build() {
        LinearLayout root=new LinearLayout(this); root.setOrientation(tv?0:1); root.setPadding(dp(tv?26:16),dp(tv?18:12),dp(tv?26:16),dp(12)); root.setBackgroundColor(BG); setContentView(root);
        LinearLayout side=root;
        if(tv) {
            side=new LinearLayout(this); side.setOrientation(1); root.addView(side,new LinearLayout.LayoutParams(dp(230),-1));
            LinearLayout content=new LinearLayout(this); content.setOrientation(1); content.setPadding(dp(22),0,0,0); root.addView(content,new LinearLayout.LayoutParams(0,-1,1)); root=content;
        }
        LinearLayout header=new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); side.addView(header);
        ImageView logo=new ImageView(this); logo.setImageResource(R.drawable.icon); header.addView(logo,new LinearLayout.LayoutParams(dp(48),dp(48)));
        LinearLayout brand=new LinearLayout(this); brand.setOrientation(1); brand.setPadding(dp(12),0,0,0);
        TextView title=text("FTP PLAYER",22,TEXT); title.setTypeface(null,Typeface.BOLD); brand.addView(title);
        brand.addView(text("Twoja prywatna biblioteka",13,MUTED)); header.addView(brand,new LinearLayout.LayoutParams(0,dp(64),1));
        if(!tv) header.addView(button("＋",()->editAccount(null)),new LinearLayout.LayoutParams(dp(50),dp(48)));
        LinearLayout servers=new LinearLayout(this); servers.setGravity(Gravity.CENTER_VERTICAL); if(tv) servers.setOrientation(1); side.addView(servers);
        accountPicker=new Spinner(this); servers.addView(accountPicker,tv?new LinearLayout.LayoutParams(-1,dp(56)):new LinearLayout.LayoutParams(0,dp(56),1));
        servers.addView(button("Ustawienia",this::settings),new LinearLayout.LayoutParams(tv?-1:dp(115),dp(46)));
        if(tv) { side.addView(button("＋ Dodaj serwer",()->editAccount(null)),new LinearLayout.LayoutParams(-1,dp(46))); side.addView(text("HTTPS / FTPS / FTP",14,MUTED),new LinearLayout.LayoutParams(-1,dp(40))); }
        accountPicker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
            @Override public void onItemSelected(android.widget.AdapterView<?> p,View v,int index,long id) {
                if(selecting||index>=accounts.length()) return;
                JSONObject selected=accounts.optJSONObject(index);
                if(account==null||!selected.optString("id").equals(account.optString("id"))) { account=selected; navigate("/"); }
            }
        });
        LinearLayout navigation=new LinearLayout(this); navigation.setGravity(Gravity.CENTER_VERTICAL); root.addView(navigation);
        navigation.addView(button("↑",this::up),new LinearLayout.LayoutParams(dp(48),dp(46)));
        location=text("Wybierz serwer",tv?20:16,TEXT); location.setPadding(dp(12),0,dp(8),0); location.setSingleLine(true); location.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        navigation.addView(location,new LinearLayout.LayoutParams(0,dp(54),1));
        navigation.addView(button("↻",()->navigate(path)),new LinearLayout.LayoutParams(dp(48),dp(46)));
        EditText search=new EditText(this); search.setSingleLine(true); search.setTextColor(TEXT); search.setHintTextColor(MUTED); search.setHint("Szukaj w tym folderze"); root.addView(search,new LinearLayout.LayoutParams(-1,dp(52)));
        search.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s,int st,int c,int a) {} public void onTextChanged(CharSequence s,int st,int before,int count) { query=s.toString().toLowerCase(Locale.ROOT); render(); } public void afterTextChanged(Editable e) {} });
        LinearLayout filters=new LinearLayout(this); root.addView(filters);
        String[] names={"Wszystko","Wideo","Audio","Zdjęcia"}, kinds={"all","video","audio","image"};
        for(int i=0;i<names.length;i++) { final String filter=kinds[i]; LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(44),1); lp.setMargins(dp(2),dp(6),dp(2),dp(6)); filters.addView(button(names[i],()->{kind=filter;render();}),lp); }
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); progress.setIndeterminate(true); progress.setVisibility(View.GONE); root.addView(progress,new LinearLayout.LayoutParams(-1,dp(3)));
        FrameLayout browser=new FrameLayout(this); root.addView(browser,new LinearLayout.LayoutParams(-1,0,1));
        items=new ListView(this); items.setDividerHeight(dp(5)); items.setCacheColorHint(BG); items.setDrawSelectorOnTop(true);
        StateListDrawable selector=new StateListDrawable(); selector.addState(new int[]{android.R.attr.state_pressed},background(true)); selector.addState(new int[]{android.R.attr.state_focused},background(true)); selector.addState(new int[]{android.R.attr.state_selected},background(true)); selector.addState(new int[]{},background(false)); items.setSelector(selector);
        browser.addView(items,new FrameLayout.LayoutParams(-1,-1));
        empty=text("Dodaj serwer przyciskiem ＋",18,MUTED); empty.setGravity(Gravity.CENTER); browser.addView(empty,new FrameLayout.LayoutParams(-1,-1)); items.setEmptyView(empty);
        fileAdapter=new BaseAdapter() {
            public int getCount() { return visible.size(); }
            public Object getItem(int index) { return visible.get(index); }
            public long getItemId(int index) { return index; }
            public View getView(int index,View reused,android.view.ViewGroup parent) {
                JSONObject entry=visible.get(index); String type=kind(entry);
                LinearLayout row;
                if(reused instanceof LinearLayout) row=(LinearLayout)reused;
                else {
                    row=new LinearLayout(MainActivity.this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(14),dp(6),dp(14),dp(6));
                    row.addView(text("",25,ACCENT),new LinearLayout.LayoutParams(dp(44),-1));
                    LinearLayout description=new LinearLayout(MainActivity.this); description.setOrientation(1);
                    TextView title=text("",tv?20:17,TEXT); title.setMaxLines(2); description.addView(title);
                    description.addView(text("",12,MUTED)); row.addView(description,new LinearLayout.LayoutParams(0,-2,1));
                }
                ((TextView)row.getChildAt(0)).setText(type.equals("folder")?"▸":type.equals("video")?"▶":type.equals("audio")?"♫":type.equals("image")?"▧":"·");
                LinearLayout description=(LinearLayout)row.getChildAt(1); ((TextView)description.getChildAt(0)).setText(entry.optString("name"));
                TextView info=(TextView)description.getChildAt(1); info.setVisibility(type.equals("folder")?View.GONE:View.VISIBLE);
                info.setText(type.toUpperCase(Locale.ROOT)+(entry.optLong("size")>0?" · "+android.text.format.Formatter.formatShortFileSize(MainActivity.this,entry.optLong("size")):""));
                row.setMinimumHeight(dp(type.equals("folder")?(tv?54:48):(tv?72:76)));
                return row;
            }
        };
        items.setAdapter(fileAdapter); items.setOnItemClickListener((parent,view,index,id)->open(visible.get(index)));
        status=text("Gotowy",13,MUTED); status.setPadding(0,dp(8),0,0); root.addView(status,new LinearLayout.LayoutParams(-1,dp(38)));
    }
    private void refreshAccounts() {
        selecting=true; ArrayList<String> names=new ArrayList<>();
        for(int i=0;i<accounts.length();i++) names.add(accounts.optJSONObject(i).optString("name"));
        if(names.isEmpty()) names.add("Dodaj serwer");
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names);
        accountPicker.setAdapter(adapter);
        if(account!=null) for(int i=0;i<accounts.length();i++) if(accounts.optJSONObject(i).optString("id").equals(account.optString("id"))) accountPicker.setSelection(i);
        selecting=false;
    }
    private void settings() {
        if(account==null) { editAccount(null); return; }
        new AlertDialog.Builder(this).setTitle(account.optString("name")).setItems(new String[]{"Edytuj konto","Dodaj konto","Usuń konto","O aplikacji"},(d,index)-> {
            if(index==0) editAccount(account); if(index==1) editAccount(null);
            if(index==2) new AlertDialog.Builder(this).setMessage("Usunąć zapisane konto? Pliki serwera pozostaną bez zmian.").setNegativeButton("Anuluj",null).setPositiveButton("Usuń",(dialog,which)-> {
                JSONArray proposed=new JSONArray(); for(int i=0;i<accounts.length();i++) if(!accounts.optJSONObject(i).optString("id").equals(account.optString("id"))) proposed.put(accounts.optJSONObject(i));
                if(save(proposed)) { generation++; account=null; entries=new JSONArray(); refreshAccounts(); render(); location.setText("Wybierz serwer"); }
            }).show();
            if(index==3) new AlertDialog.Builder(this).setTitle("FTP Player 1.0.1").setMessage("FTP / FTPS / HTTPS index of\nWbudowany libVLC: wideo i audio.\nSterowanie dotykiem i pilotem TV.\nHasła: Android Keystore.\nNie każdy profil 4K ma sprzętowy dekoder na każdym urządzeniu.").setPositiveButton("OK",null).show();
        }).show();
    }
    private void editAccount(JSONObject current) {
        LinearLayout box=new LinearLayout(this); box.setOrientation(1); box.setPadding(dp(20),dp(6),dp(20),dp(6));
        String[] keys={"name","address","username","password"}, hints={"Nazwa konta","https://serwer/media/ lub ftps://serwer/","Login (pusty = anonimowy)","Hasło"};
        EditText[] fields=new EditText[4];
        for(int i=0;i<4;i++) { fields[i]=new EditText(this); fields[i].setSingleLine(true); fields[i].setHint(hints[i]); fields[i].setTextSize(16); if(i==3) fields[i].setInputType(129); if(current!=null) fields[i].setText(current.optString(keys[i])); box.addView(fields[i],new LinearLayout.LayoutParams(-1,dp(56))); }
        box.addView(text("HTTPS: lista katalogów i Basic Auth.\nFTPS: TLS na porcie 21. FTP/HTTP nie szyfrują danych.",12,MUTED));
        ScrollView scroll=new ScrollView(this); scroll.addView(box);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(current==null?"Dodaj serwer":"Edytuj konto").setView(scroll).setNegativeButton("Anuluj",null).setPositiveButton("Zapisz",null).create();
        dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(b-> {
            try {
                JSONObject row=new JSONObject(); row.put("id",current==null?UUID.randomUUID().toString():current.getString("id"));
                for(int i=0;i<4;i++) { String value=fields[i].getText().toString(); if(value.indexOf('\r')>=0||value.indexOf('\n')>=0||value.indexOf('\0')>=0) throw new IOException("Niedozwolony znak"); row.put(keys[i],value); }
                if(row.optString("name").trim().isEmpty()) row.put("name","Mój serwer");
                new RemoteClient(row);
                JSONArray proposed=new JSONArray(); boolean replaced=false;
                for(int i=0;i<accounts.length();i++) { JSONObject old=accounts.optJSONObject(i); if(old.optString("id").equals(row.getString("id"))) { proposed.put(row); replaced=true; } else proposed.put(old); }
                if(!replaced) proposed.put(row);
                if(save(proposed)) { account=row; refreshAccounts(); navigate("/"); dialog.dismiss(); }
            } catch(Exception error) { toast("Sprawdź adres serwera i dane konta"); }
        })); dialog.show();
    }
    private boolean save(JSONArray proposed) {
        try { store.save(proposed); accounts=proposed; return true; }
        catch(Exception error) { toast("Nie udało się bezpiecznie zapisać konta"); return false; }
    }
    void navigate(String target) {
        if(account==null) return;
        path=target; final int request=++generation; final JSONObject selected=account;
        getPreferences(0).edit().putString("account",account.optString("id")).apply();
        location.setText(target.equals("/")?account.optString("name"):target);
        entries=new JSONArray(); render(); progress.setVisibility(View.VISIBLE); status.setText("Wczytywanie katalogu…");
        state().workers.execute(()-> {
            try { JSONArray result=new RemoteClient(selected).list(target);
                ui.post(()-> { if(closed||request!=generation) return; entries=result; progress.setVisibility(View.GONE); render(); status.setText(result.length()+" elementów · "+selected.optString("address").split(":",2)[0].toUpperCase(Locale.ROOT)); });
            } catch(Exception error) { ui.post(()-> { if(closed||request!=generation) return; progress.setVisibility(View.GONE); status.setText("Nie udało się połączyć. Sprawdź konto i odśwież folder."); }); }
        });
    }
    static String kind(JSONObject entry) {
        if(entry.optBoolean("directory")) return "folder";
        String ext=entry.optString("name").replaceAll(".*\\.","").toLowerCase(Locale.ROOT);
        if(Arrays.asList("mp4","mkv","avi","mov","wmv","mpg","mpeg","m1v","m2v","rm","rmvb","flv","webm","m4v","ts","mts","m2ts","vob","ogv","3gp","hevc","xvid","divx","asf").contains(ext)) return "video";
        if(Arrays.asList("mp3","flac","wav","aac","m4a","ogg","opus","wma","aiff","ape","alac","ac3","dts","mka","amr").contains(ext)) return "audio";
        if(Arrays.asList("jpg","jpeg","png","webp","gif","bmp","tif","tiff","avif","heic","heif").contains(ext)) return "image";
        return "file";
    }
    private void render() {
        if(items==null) return;
        visible.clear();
        for(int i=0;i<entries.length();i++) {
            JSONObject entry=entries.optJSONObject(i); String type=kind(entry);
            if(!entry.optString("name").toLowerCase(Locale.ROOT).contains(query)||(!kind.equals("all")&&!type.equals("folder")&&!type.equals(kind))) continue;
            visible.add(entry);
        }
        empty.setText(account==null?"Dodaj serwer przyciskiem ＋":"Brak plików pasujących do filtra");
        fileAdapter.notifyDataSetChanged();
    }
    private void open(JSONObject entry) {
        if(entry.optBoolean("directory")) { navigate(entry.optString("path")); return; }
        String type=kind(entry);
        if(type.equals("file")) { toast("Ten plik nie jest rozpoznanym multimedium"); return; }
        try {
            JSONArray playlist=new JSONArray(); String selectedUrl="";
            for(int i=0;i<entries.length();i++) {
                JSONObject item=entries.optJSONObject(i); String k=kind(item);
                if(type.equals("image")?!k.equals("image"):!(k.equals("audio")||k.equals("video"))) continue;
                JSONObject copy=new JSONObject(item.toString()); copy.put("url",state().server.register(account,item)); playlist.put(copy);
                if(item.optString("id").equals(entry.optString("id"))) selectedUrl=copy.getString("url");
            }
            File file=new File(getCacheDir(),"playlist-"+UUID.randomUUID()+".json");
            try(FileOutputStream output=new FileOutputStream(file)) { output.write(playlist.toString().getBytes("UTF-8")); }
            Intent intent=new Intent(this,type.equals("image")?PhotoActivity.class:NativePlayerActivity.class);
            intent.putExtra("url",selectedUrl).putExtra("file_id",entry.optString("id")).putExtra("title",entry.optString("name"))
                .putExtra("playlist_path",file.getAbsolutePath()).putExtra("size",entry.optLong("size")); startActivity(intent);
        } catch(Exception error) { toast("Nie udało się otworzyć pliku"); }
    }
    void up() { if(path.equals("/")) return; int slash=path.lastIndexOf('/'); navigate(slash<=0?"/":path.substring(0,slash)); }
    void toast(String message) { Toast.makeText(this,message,Toast.LENGTH_LONG).show(); }
    @Override public void onBackPressed() { if(!path.equals("/")) up(); else super.onBackPressed(); }
    @Override public void onSaveInstanceState(Bundle state) { state.putString("path",path); super.onSaveInstanceState(state); }
    @Override protected void onDestroy() { closed=true; generation++; ui.removeCallbacksAndMessages(null); super.onDestroy(); }
}
