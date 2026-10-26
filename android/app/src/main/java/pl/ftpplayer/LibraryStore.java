package pl.ftpplayer;

import android.content.Context;
import org.json.*;
import java.util.*;
import java.security.MessageDigest;

/** Per-server navigation and favorites; no credentials are stored here. */
final class LibraryStore {
    private final android.content.SharedPreferences preferences;
    LibraryStore(Context context) { preferences=context.getSharedPreferences("library",0); }
    static String key(JSONObject account) { return hash(account.optString("id")+":"+account.optString("address")+":"+account.optString("username")); }
    static String hash(String value) {
        try {
            byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));
            StringBuilder encoded=new StringBuilder(); for(byte b:bytes)encoded.append(String.format(Locale.ROOT,"%02x",b&255)); return encoded.toString();
        } catch(Exception error) { throw new IllegalStateException(error); }
    }
    JSONObject state(JSONObject account) {
        try { return new JSONObject(preferences.getString(key(account),"{}")); }
        catch(Exception error) { return new JSONObject(); }
    }
    private void save(JSONObject account,JSONObject state) { preferences.edit().putString(key(account),state.toString()).apply(); }
    String startup(JSONObject account) { JSONObject s=state(account);return s.optString("start").isEmpty()?s.optString("last","/"):s.optString("start"); }
    void visited(JSONObject account,String path) { try { JSONObject s=state(account);s.put("last",path);save(account,s); } catch(Exception ignored) {} }
    void start(JSONObject account,String path) { try { JSONObject s=state(account);s.put("start",path);save(account,s); } catch(Exception ignored) {} }
    JSONArray folders(JSONObject account) { JSONArray a=state(account).optJSONArray("folders");return a==null?new JSONArray():a; }
    JSONArray files(JSONObject account) { JSONArray a=state(account).optJSONArray("files");return a==null?new JSONArray():a; }
    boolean favorite(JSONObject account,String path,boolean folder) {
        JSONArray rows=folder?folders(account):files(account);
        for(int i=0;i<rows.length();i++) if((folder?rows.optString(i):rows.optJSONObject(i).optString("path")).equals(path))return true;
        return false;
    }
    void toggle(JSONObject account,JSONObject entry,boolean folder) {
        try {
            String path=entry.getString("path");JSONArray original=folder?folders(account):files(account), rows=new JSONArray();boolean found=false;
            for(int i=0;i<original.length();i++) {
                String current=folder?original.optString(i):original.optJSONObject(i).optString("path");
                if(current.equals(path))found=true;else rows.put(original.get(i));
            }
            if(!found) { if(folder)rows.put(path);else { JSONObject copy=new JSONObject(entry.toString());copy.remove("url");rows.put(copy); } }
            JSONObject s=state(account);s.put(folder?"folders":"files",rows);save(account,s);
        } catch(Exception ignored) {}
    }
    static void sort(List<JSONObject> rows,String key,boolean descending) {
        Collections.sort(rows,(a,b)-> {
            if(a.optBoolean("directory")!=b.optBoolean("directory"))return a.optBoolean("directory")?-1:1;
            if(a.optBoolean("directory"))return natural(a.optString("name"),b.optString("name"));
            boolean ma=key.equals("size")&&!a.optBoolean("metadataKnown")&&a.optLong("size")==0||key.equals("modified")&&a.optLong("modified")==0;
            boolean mb=key.equals("size")&&!b.optBoolean("metadataKnown")&&b.optLong("size")==0||key.equals("modified")&&b.optLong("modified")==0;
            if(ma!=mb)return ma?1:-1;
            int compare=key.equals("size")||key.equals("modified")?Long.compare(a.optLong(key),b.optLong(key)):key.equals("ext")?extension(a).compareTo(extension(b)):natural(a.optString("name"),b.optString("name"));
            if(compare!=0)return descending?-compare:compare;
            return natural(a.optString("name"),b.optString("name"));
        });
    }
    static String extension(JSONObject entry) { String name=entry.optString("name");int dot=name.lastIndexOf('.');return dot<0?"":name.substring(dot+1).toLowerCase(Locale.ROOT); }
    static int natural(String a,String b) {
        String[] x=a.toLowerCase(Locale.ROOT).split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)"), y=b.toLowerCase(Locale.ROOT).split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)");
        for(int i=0;i<Math.min(x.length,y.length);i++) { int result;
            if(x[i].matches("\\d+")&&y[i].matches("\\d+"))result=new java.math.BigInteger(x[i]).compareTo(new java.math.BigInteger(y[i]));else result=x[i].compareTo(y[i]);
            if(result!=0)return result;
        }
        return Integer.compare(x.length,y.length);
    }
}
