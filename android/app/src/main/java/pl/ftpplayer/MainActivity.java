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
    private boolean selecting, tv, closed, compact, paused;
    private LibraryStore library;
    private VideoThumbnails thumbnails;
    private String sortKey="name", pendingOpen="", pendingDownloadAccount="";
    private boolean descending, slides=true, cards, metadataPending, metadataRetry;
    private JSONObject pendingDownload;
    private int slideFrame;
    private static final class Row { int type;ImageView thumbnail;TextView symbol,title,info;JSONObject entry; }
    private final Runnable slideshow=new Runnable(){public void run(){if(closed||paused)return;slideFrame=(slideFrame+1)%3;refreshImages();ui.postDelayed(this,1100);}};
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
        library=new LibraryStore(this);sortKey=getPreferences(0).getString("sort","name");descending=getPreferences(0).getBoolean("descending",false);slides=getPreferences(0).getBoolean("slides",true);cards=getPreferences(0).getBoolean("cards",false);
        if(saved!=null){pendingDownloadAccount=saved.getString("downloadAccount","");try{String data=saved.getString("download");if(data!=null)pendingDownload=new JSONObject(data);}catch(Exception ignored){}}
        store=new AccountStore(this);
        try { accounts=store.load(); } catch(Exception error) { toast("Nie udało się odczytać kont. Istniejące dane zachowano."); }
        build(); refreshAccounts();
        if(accounts.length()>0) {
            String selected=getPreferences(0).getString("account",""); int index=0;
            for(int i=0;i<accounts.length();i++) if(accounts.optJSONObject(i).optString("id").equals(selected)) index=i;
            account=accounts.optJSONObject(index); selecting=true; accountPicker.setSelection(index); selecting=false;
            path=saved==null?library.startup(account):saved.getString("path","/"); navigate(path);
        } else status.setText("Dodaj pierwszy serwer, aby otworzyć swoją bibliotekę.");
    }
    private void build() {
        if(thumbnails!=null)thumbnails.close();
        compact=!tv&&getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
        boolean wide=tv||compact;
        LinearLayout root=new LinearLayout(this);root.setOrientation(wide?0:1);root.setPadding(dp(wide?12:16),dp(compact?6:12),dp(wide?12:16),dp(compact?4:10));root.setBackgroundColor(BG);
        FrameLayout stage=new FrameLayout(this);setContentView(stage);
        TextureView preview=new TextureView(this);stage.addView(preview,new FrameLayout.LayoutParams(dp(320),dp(180)));stage.addView(root,new FrameLayout.LayoutParams(-1,-1));
        thumbnails=new VideoThumbnails(this,preview,this::refreshImages);
        LinearLayout side=root;
        if(wide) {
            side=new LinearLayout(this); side.setOrientation(1); root.addView(side,new LinearLayout.LayoutParams(dp(compact?170:230),-1));
            LinearLayout content=new LinearLayout(this); content.setOrientation(1); content.setPadding(dp(22),0,0,0); root.addView(content,new LinearLayout.LayoutParams(0,-1,1)); root=content;
        }
        LinearLayout header=new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); side.addView(header);
        ImageView logo=new ImageView(this); logo.setImageResource(R.drawable.icon); header.addView(logo,new LinearLayout.LayoutParams(dp(compact?30:48),dp(compact?30:48)));
        LinearLayout brand=new LinearLayout(this); brand.setOrientation(1); brand.setPadding(dp(12),0,0,0);
        TextView title=text("FTP PLAYER",compact?17:22,TEXT); title.setTypeface(null,Typeface.BOLD); brand.addView(title);
        if(!compact)brand.addView(text("Twoja prywatna biblioteka",13,MUTED)); header.addView(brand,new LinearLayout.LayoutParams(0,dp(compact?40:64),1));
        if(!wide) header.addView(button("＋",()->editAccount(null)),new LinearLayout.LayoutParams(dp(50),dp(48)));
        LinearLayout servers=new LinearLayout(this); servers.setGravity(Gravity.CENTER_VERTICAL); if(wide) servers.setOrientation(1); side.addView(servers);
        accountPicker=new Spinner(this); servers.addView(accountPicker,wide?new LinearLayout.LayoutParams(-1,dp(compact?42:56)):new LinearLayout.LayoutParams(0,dp(56),1));
        servers.addView(button("Ustawienia",this::settings),new LinearLayout.LayoutParams(wide?-1:dp(115),dp(compact?38:46)));
        if(wide) { side.addView(button("＋ Dodaj serwer",()->editAccount(null)),new LinearLayout.LayoutParams(-1,dp(46))); side.addView(button("★ Zapisane",this::savedLocations),new LinearLayout.LayoutParams(-1,dp(compact?38:46))); }
        accountPicker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
            @Override public void onItemSelected(android.widget.AdapterView<?> p,View v,int index,long id) {
                if(selecting||index>=accounts.length()) return;
                JSONObject selected=accounts.optJSONObject(index);
                if(account==null||!selected.optString("id").equals(account.optString("id"))) { account=selected; navigate(library.startup(account)); }
            }
        });
        LinearLayout navigation=new LinearLayout(this); navigation.setGravity(Gravity.CENTER_VERTICAL); root.addView(navigation);
        navigation.addView(button("↑",this::up),new LinearLayout.LayoutParams(dp(48),dp(46)));
        location=text("Wybierz serwer",tv?20:16,TEXT); location.setPadding(dp(12),0,dp(8),0); location.setSingleLine(true); location.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        navigation.addView(location,new LinearLayout.LayoutParams(0,dp(compact?40:54),1));
        navigation.addView(button("↻",()->navigate(path)),new LinearLayout.LayoutParams(dp(48),dp(46)));
        navigation.addView(button("★",this::folderActions),new LinearLayout.LayoutParams(dp(44),dp(compact?38:46)));
        if(compact)navigation.addView(button("⌕",this::searchDialog),new LinearLayout.LayoutParams(dp(44),dp(38)));
        EditText search=new EditText(this); search.setSingleLine(true); search.setTextColor(TEXT); search.setHintTextColor(MUTED); search.setHint("Szukaj w tym folderze"); if(!compact)root.addView(search,new LinearLayout.LayoutParams(-1,dp(46)));
        search.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s,int st,int c,int a) {} public void onTextChanged(CharSequence s,int st,int before,int count) { query=s.toString().toLowerCase(Locale.ROOT); render(); } public void afterTextChanged(Editable e) {} });
        LinearLayout filters=new LinearLayout(this);filters.setGravity(Gravity.CENTER_VERTICAL);root.addView(filters);
        Spinner types=new Spinner(this);String[] names={"Wszystko","Wideo","Audio","Zdjęcia"},kinds={"all","video","audio","image"};
        types.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,names));
        for(int i=0;i<kinds.length;i++)if(kinds[i].equals(kind))types.setSelection(i);
        types.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){}public void onItemSelected(android.widget.AdapterView<?> p,View view,int index,long id){kind=kinds[index];render();}});
        filters.addView(types,new LinearLayout.LayoutParams(0,dp(42),1));
        filters.addView(button("↕ Sortuj",this::chooseSort),new LinearLayout.LayoutParams(dp(compact?108:128),dp(38)));
        filters.addView(button("▦",this::toggleView),new LinearLayout.LayoutParams(dp(44),dp(38)));
        filters.addView(button("⋯",this::browserOptions),new LinearLayout.LayoutParams(dp(44),dp(38)));
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); progress.setIndeterminate(true); progress.setVisibility(View.GONE); root.addView(progress,new LinearLayout.LayoutParams(-1,dp(3)));
        FrameLayout browser=new FrameLayout(this); root.addView(browser,new LinearLayout.LayoutParams(-1,0,1));
        items=new ListView(this); items.setDividerHeight(dp(5)); items.setCacheColorHint(BG); items.setDrawSelectorOnTop(true);
        StateListDrawable selector=new StateListDrawable(); selector.addState(new int[]{android.R.attr.state_pressed},background(true)); selector.addState(new int[]{android.R.attr.state_focused},background(true)); selector.addState(new int[]{android.R.attr.state_selected},background(true)); selector.addState(new int[]{},background(false)); items.setSelector(selector);
        browser.addView(items,new FrameLayout.LayoutParams(-1,-1));
        empty=text("Dodaj serwer przyciskiem ＋",18,MUTED); empty.setGravity(Gravity.CENTER); browser.addView(empty,new FrameLayout.LayoutParams(-1,-1)); items.setEmptyView(empty);
        fileAdapter=new BaseAdapter() {
            public int getCount() { return visible.size(); }
            public int getViewTypeCount(){return 3;}
            public int getItemViewType(int index){return kind(visible.get(index)).equals("folder")?0:cards?2:1;}
            public Object getItem(int index) { return visible.get(index); }
            public long getItemId(int index) { return index; }
            public View getView(int index,View reused,android.view.ViewGroup parent) {
                JSONObject entry=visible.get(index);String type=kind(entry);int viewType=getItemViewType(index);LinearLayout row;Row holder;
                if(reused instanceof LinearLayout&&((Row)reused.getTag()).type==viewType){row=(LinearLayout)reused;holder=(Row)row.getTag();}
                else {
                    row=new LinearLayout(MainActivity.this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(8),dp(4),dp(8),dp(4));holder=new Row();holder.type=viewType;row.setTag(holder);row.setOrientation(viewType==2?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
                    FrameLayout artwork=new FrameLayout(MainActivity.this);holder.thumbnail=new ImageView(MainActivity.this);holder.thumbnail.setScaleType(ImageView.ScaleType.FIT_CENTER);artwork.addView(holder.thumbnail,new FrameLayout.LayoutParams(-1,-1));holder.symbol=text("",25,ACCENT);holder.symbol.setGravity(Gravity.CENTER);artwork.addView(holder.symbol,new FrameLayout.LayoutParams(-1,-1));row.addView(artwork,new LinearLayout.LayoutParams(viewType==2?-1:dp(tv?112:88),dp(54)));
                    LinearLayout description=new LinearLayout(MainActivity.this);description.setOrientation(1);description.setPadding(dp(10),0,0,0);holder.title=text("",tv?20:16,TEXT);holder.title.setMaxLines(2);description.addView(holder.title);holder.info=text("",12,MUTED);holder.info.setSingleLine(true);holder.info.setEllipsize(TextUtils.TruncateAt.END);description.addView(holder.info);row.addView(description,viewType==2?new LinearLayout.LayoutParams(-1,-2):new LinearLayout.LayoutParams(0,-2,1));
                }
                LinearLayout.LayoutParams artworkParams=(LinearLayout.LayoutParams)row.getChildAt(0).getLayoutParams();artworkParams.height=viewType==2?Math.max(dp(130),(Math.max(dp(260),items.getWidth())-dp(16))*9/16):dp(type.equals("folder")?28:54);row.getChildAt(0).setLayoutParams(artworkParams);
                holder.entry=entry;holder.title.setText((account!=null&&library.favorite(account,entry.optString("path"),false)?"★ ":"")+entry.optString("name"));
                holder.symbol.setText(type.equals("folder")?"▸":type.equals("video")?"▶":type.equals("audio")?"♫":type.equals("image")?"▧":"·");
                holder.info.setVisibility(type.equals("folder")?View.GONE:View.VISIBLE);
                String size=entry.optBoolean("metadataKnown")||entry.optLong("size")>0?android.text.format.Formatter.formatShortFileSize(MainActivity.this,entry.optLong("size")):"—";
                holder.info.setText(LibraryStore.extension(entry).toUpperCase(Locale.ROOT)+"  ·  "+size+(entry.optLong("modified")>0?"  ·  "+new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.getDefault()).format(new Date(entry.optLong("modified"))):""));
                row.setMinimumHeight(dp(type.equals("folder")?(tv?48:44):(tv?76:72)));
                bindImage(holder);return row;
            }
        };
        items.setAdapter(fileAdapter); items.setOnItemClickListener((parent,view,index,id)->open(visible.get(index)));
        items.setOnItemLongClickListener((parent,view,index,id)->{entryActions(visible.get(index));return true;});
        status=text("Gotowy",13,MUTED); status.setPadding(0,dp(8),0,0); root.addView(status,new LinearLayout.LayoutParams(-1,dp(compact?24:32)));
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
            if(index==3) new AlertDialog.Builder(this).setTitle("FTP Player 1.1.0").setMessage("FTP / FTPS / HTTPS index of\nWbudowany libVLC: wideo i audio.\nSterowanie dotykiem i pilotem TV.\nHasła: Android Keystore.\nNie każdy profil 4K ma sprzętowy dekoder na każdym urządzeniu.").setPositiveButton("OK",null).show();
        }).show();
    }
    private void editAccount(JSONObject current) {
        LinearLayout box=new LinearLayout(this); box.setOrientation(1); box.setPadding(dp(20),dp(6),dp(20),dp(6));
        String[] keys={"name","address","username","password"}, hints={"Nazwa konta","https://serwer/media/ lub ftps://serwer/","Login (pusty = anonimowy)","Hasło"};
        EditText[] fields=new EditText[4];
        for(int i=0;i<4;i++) { fields[i]=new EditText(this); fields[i].setSingleLine(true); fields[i].setHint(hints[i]); fields[i].setTextSize(16); if(i==3) fields[i].setInputType(129); if(current!=null) fields[i].setText(current.optString(keys[i])); box.addView(fields[i],new LinearLayout.LayoutParams(-1,dp(compact?42:56))); }
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
        if(thumbnails!=null)thumbnails.cancel();
        if(account==null) return;
        path=target; final int request=++generation; final JSONObject selected=account;
        getPreferences(0).edit().putString("account",account.optString("id")).apply();
        location.setText(target.equals("/")?account.optString("name"):target);
        entries=new JSONArray(); render(); progress.setVisibility(View.VISIBLE); status.setText("Wczytywanie katalogu…");
        state().workers.execute(()-> {
            try { JSONArray result=new RemoteClient(selected).list(target);
                ui.post(()-> { if(closed||request!=generation) return; entries=result; library.visited(selected,target); progress.setVisibility(View.GONE); render(); metadata(result,request,selected); if(!pendingOpen.isEmpty()){String wanted=pendingOpen;pendingOpen="";JSONObject found=null;for(int i=0;i<result.length();i++)if(result.optJSONObject(i).optString("path").equals(wanted))found=result.optJSONObject(i);if(found!=null)open(found);else toast("Ulubiony plik jest niedostępny. Otworzono jego folder.");} status.setText(result.length()+" elementów · "+selected.optString("address").split(":",2)[0].toUpperCase(Locale.ROOT)); });
            } catch(Exception error) { ui.post(()-> { if(closed||request!=generation) return; progress.setVisibility(View.GONE); if(error instanceof RemoteClient.UnavailableException&&!target.equals("/")){pendingOpen="";navigate("/");toast("Zapisany folder jest niedostępny. Otwieram katalog główny.");return;} status.setText("Nie udało się połączyć. Sprawdź konto i odśwież folder."); }); }
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
        LibraryStore.sort(visible,sortKey,descending);
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
    private void bindImage(Row row){
        if(account==null||thumbnails==null||!kind(row.entry).equals("video")){row.thumbnail.setImageDrawable(null);row.symbol.setVisibility(View.VISIBLE);return;}
        android.graphics.Bitmap[] frames=thumbnails.get(account,row.entry);
        if(frames!=null){row.thumbnail.setImageBitmap(frames[slides?slideFrame%3:0]);row.symbol.setVisibility(View.GONE);}
        else{row.thumbnail.setImageDrawable(null);row.symbol.setVisibility(View.VISIBLE);if(row.entry.optBoolean("sizeExact"))thumbnails.request(account,row.entry);}
    }
    private void refreshImages(){if(items==null)return;for(int i=0;i<items.getChildCount();i++){Object tag=items.getChildAt(i).getTag();if(tag instanceof Row)bindImage((Row)tag);}}
    private void metadata(JSONArray rows,int request,JSONObject selected){
        if(metadataPending){metadataRetry=true;return;}metadataPending=true;
        state().workers.execute(()->{try{for(int i=0;i<rows.length();i++){
            if(closed||paused||generation!=request)return;JSONObject entry=rows.optJSONObject(i);if(entry.optBoolean("directory")||entry.optBoolean("sizeExact"))continue;
            try{JSONObject updated=new RemoteClient(selected).stat(entry);ui.post(()->{if(closed||generation!=request)return;try{entry.put("size",updated.optLong("size")).put("modified",updated.optLong("modified")).put("metadataKnown",true).put("sizeExact",true);render();}catch(Exception ignored){}});}catch(Exception ignored){}
        }}finally{ui.post(()->{metadataPending=false;if(metadataRetry&&!closed&&!paused&&account!=null){metadataRetry=false;metadata(entries,generation,account);}});}});
    }
    private void toggleView(){cards=!cards;getPreferences(0).edit().putBoolean("cards",cards).apply();fileAdapter.notifyDataSetChanged();}
    private void chooseSort(){String[] labels={"Nazwa ↑","Nazwa ↓","Rozmiar ↑","Rozmiar ↓","Data ↑","Data ↓","Format ↑","Format ↓"};String[] keys={"name","size","modified","ext"};new AlertDialog.Builder(this).setTitle("Sortowanie — foldery zawsze pierwsze").setItems(labels,(d,index)->{sortKey=keys[index/2];descending=index%2==1;getPreferences(0).edit().putString("sort",sortKey).putBoolean("descending",descending).apply();render();}).show();}
    private void searchDialog(){EditText field=new EditText(this);field.setSingleLine(true);field.setText(query);new AlertDialog.Builder(this).setTitle("Filtruj folder").setView(field).setNegativeButton("Anuluj",null).setPositiveButton("Szukaj",(d,w)->{query=field.getText().toString().toLowerCase(Locale.ROOT);render();}).show();}
    private JSONObject folderEntry(String target){JSONObject row=new JSONObject();try{row.put("path",target).put("name",target).put("directory",true);}catch(Exception ignored){}return row;}
    private void folderActions(){if(account==null)return;new AlertDialog.Builder(this).setTitle(path).setItems(new String[]{library.favorite(account,path,true)?"Usuń zakładkę folderu":"Dodaj zakładkę folderu","Ustaw jako startowy","Startuj od ostatnio otwartego","Zakładki i ulubione"},(d,index)->{if(index==0)library.toggle(account,folderEntry(path),true);if(index==1)library.start(account,path);if(index==2)library.start(account,"");if(index==3)savedLocations();}).show();}
    private void browserOptions(){new AlertDialog.Builder(this).setTitle("Przeglądarka").setItems(new String[]{"Zakładki i ulubione",slides?"Wyłącz pokaz klatek":"Włącz pokaz 3 klatek","Pobieranie","Szukaj w folderze"},(d,index)->{if(index==0)savedLocations();if(index==1){slides=!slides;getPreferences(0).edit().putBoolean("slides",slides).apply();refreshImages();}if(index==2)downloads();if(index==3)searchDialog();}).show();}
    private void entryActions(JSONObject entry){boolean folder=entry.optBoolean("directory");new AlertDialog.Builder(this).setTitle(entry.optString("name")).setItems(folder?new String[]{"Otwórz","Dodaj/usuń zakładkę","Ustaw jako startowy"}:new String[]{"Otwórz / odtwórz","Pobierz plik",library.favorite(account,entry.optString("path"),false)?"Usuń z ulubionych":"Dodaj do ulubionych"},(d,index)->{if(index==0)open(entry);if(index==1){if(folder)library.toggle(account,entry,true);else requestDownload(entry);}if(index==2){if(folder)library.start(account,entry.optString("path"));else {library.toggle(account,entry,false);render();}}}).show();}
    private void savedLocations(){
        if(account==null)return;JSONArray folders=library.folders(account),files=library.files(account);ArrayList<String> labels=new ArrayList<>();for(int i=0;i<folders.length();i++)labels.add("▸ "+folders.optString(i));for(int i=0;i<files.length();i++)labels.add("★ "+files.optJSONObject(i).optString("name"));
        if(labels.isEmpty()){toast("Brak zapisanych folderów i plików. Przytrzymaj element lub użyj ★ przy ścieżce.");return;}
        new AlertDialog.Builder(this).setTitle("Zakładki i ulubione").setItems(labels.toArray(new String[0]),(dialog,index)->{
            boolean folder=index<folders.length();JSONObject item=folder?folderEntry(folders.optString(index)):files.optJSONObject(index-folders.length());
            new AlertDialog.Builder(this).setTitle(item.optString("name")).setItems(new String[]{"Otwórz i sprawdź dostępność","Usuń z zapisanych"},(d,action)->{
                if(action==1){library.toggle(account,item,folder);render();return;}
                if(folder)navigate(item.optString("path"));else {pendingOpen=item.optString("path");int slash=pendingOpen.lastIndexOf('/');navigate(slash<=0?"/":pendingOpen.substring(0,slash));}
            }).show();
        }).show();
    }
    private void requestDownload(JSONObject entry){
        pendingDownload=entry;pendingDownloadAccount=account.optString("id");
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},421);return;}
        chooseDownloadLocation();
    }
    private void chooseDownloadLocation(){if(pendingDownload==null)return;Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,pendingDownload.optString("name"));try{startActivityForResult(intent,420);}catch(Exception error){
        File directory=getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);if(directory==null){toast("Brak dostępnego miejsca zapisu");return;}directory.mkdirs();String safe=pendingDownload.optString("name").replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_");
        File target=new File(directory,System.currentTimeMillis()+"-"+safe);startDownload(android.net.Uri.fromFile(target));toast("Pobieranie do folderu aplikacji. Ścieżka będzie dostępna w historii pobierania.");
    }}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==421)chooseDownloadLocation();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==420&&result==RESULT_OK&&data!=null&&data.getData()!=null&&pendingDownload!=null){
        try{getContentResolver().takePersistableUriPermission(data.getData(),data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION));}catch(Exception ignored){}
        startDownload(data.getData());toast("Rozpoczęto pobieranie. Postęp jest w powiadomieniu.");
    }}
    private void startDownload(android.net.Uri destination){Intent service=new Intent(this,DownloadService.class).putExtra("account",pendingDownloadAccount).putExtra("entry",pendingDownload.toString()).putExtra("destination",destination.toString());if(Build.VERSION.SDK_INT>=26)startForegroundService(service);else startService(service);pendingDownload=null;}
    private void downloads(){try{JSONArray rows=new JSONArray(getSharedPreferences("downloads",0).getString("history","[]"));ArrayList<String> lines=new ArrayList<>();for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);lines.add(row.optString("name")+"\n"+row.optString("result"));}new AlertDialog.Builder(this).setTitle("Historia pobierania").setItems(lines.isEmpty()?new String[]{"Brak zakończonych pobrań. Postęp bieżącego jest w powiadomieniu."}:lines.toArray(new String[0]),(dialog,index)->{JSONObject row=rows.optJSONObject(index);if(row!=null)new AlertDialog.Builder(this).setTitle(row.optString("name")).setMessage(row.optString("result")+"\n"+row.optString("uri")).setPositiveButton("OK",null).show();}).setPositiveButton("OK",null).show();}catch(Exception ignored){}}
    @Override protected void onResume(){super.onResume();paused=false;ui.removeCallbacks(slideshow);ui.post(slideshow);if(fileAdapter!=null)fileAdapter.notifyDataSetChanged();if(account!=null&&entries.length()>0)metadata(entries,generation,account);}
    @Override protected void onPause(){paused=true;ui.removeCallbacks(slideshow);if(thumbnails!=null)thumbnails.cancel();super.onPause();}
    void up() { if(path.equals("/")) return; int slash=path.lastIndexOf('/'); navigate(slash<=0?"/":path.substring(0,slash)); }
    void toast(String message) { Toast.makeText(this,message,Toast.LENGTH_LONG).show(); }
    @Override public boolean onKeyDown(int key,KeyEvent event){if(key==KeyEvent.KEYCODE_MENU&&items!=null){int selected=items.getSelectedItemPosition();if(selected>=0&&selected<visible.size()){entryActions(visible.get(selected));return true;}}return super.onKeyDown(key,event);}
    @Override public void onBackPressed() { if(!path.equals("/")) up(); else super.onBackPressed(); }
    @Override public void onSaveInstanceState(Bundle state) { state.putString("path",path);state.putString("downloadAccount",pendingDownloadAccount);if(pendingDownload!=null)state.putString("download",pendingDownload.toString()); super.onSaveInstanceState(state); }
    @Override protected void onDestroy() { closed=true; generation++; if(thumbnails!=null)thumbnails.close(); ui.removeCallbacksAndMessages(null); super.onDestroy(); }
}
