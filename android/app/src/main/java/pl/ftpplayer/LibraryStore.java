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
            char[] hex="0123456789abcdef".toCharArray(),encoded=new char[bytes.length*2];for(int i=0;i<bytes.length;i++){int b=bytes[i]&255;encoded[i*2]=hex[b>>>4];encoded[i*2+1]=hex[b&15];}return new String(encoded);
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
    static final class SortRow {
        final JSONObject entry;final String name,extension;final boolean directory,known;final long size,modified;
        SortRow(JSONObject entry){this.entry=entry;synchronized(entry){name=entry.optString("name").toLowerCase(Locale.ROOT);extension=extension(entry);directory=entry.optBoolean("directory");known=entry.optBoolean("metadataKnown");size=entry.optLong("size");modified=entry.optLong("modified");}}
    }
    static ArrayList<SortRow> snapshot(List<JSONObject> rows){ArrayList<SortRow> copy=new ArrayList<>(rows.size());for(JSONObject entry:rows)copy.add(new SortRow(entry));return copy;}
    static ArrayList<JSONObject> sorted(ArrayList<SortRow> rows,String key,boolean descending){
        Collections.sort(rows,(a,b)->{
            if(a.directory!=b.directory)return a.directory?-1:1;if(a.directory)return naturalFolded(a.name,b.name);
            boolean ma=key.equals("size")&&!a.known&&a.size==0||key.equals("modified")&&a.modified==0,mb=key.equals("size")&&!b.known&&b.size==0||key.equals("modified")&&b.modified==0;if(ma!=mb)return ma?1:-1;
            int compare=key.equals("size")?Long.compare(a.size,b.size):key.equals("modified")?Long.compare(a.modified,b.modified):key.equals("ext")?a.extension.compareTo(b.extension):naturalFolded(a.name,b.name);
            return compare!=0?(descending?-compare:compare):naturalFolded(a.name,b.name);
        });
        ArrayList<JSONObject> result=new ArrayList<>(rows.size());for(SortRow row:rows)result.add(row.entry);return result;
    }
    static void sort(List<JSONObject> rows,String key,boolean descending){ArrayList<JSONObject> result=sorted(snapshot(rows),key,descending);rows.clear();rows.addAll(result);}
    static String extension(JSONObject entry) { String name=entry.optString("name");int dot=name.lastIndexOf('.');return dot<0?"":name.substring(dot+1).toLowerCase(Locale.ROOT); }
    static int natural(String a,String b) {
        return naturalFolded(a.toLowerCase(Locale.ROOT),b.toLowerCase(Locale.ROOT));
    }
    private static int naturalFolded(String a,String b){
        int i=0,j=0;
        while(i<a.length()&&j<b.length()){
            char x=a.charAt(i),y=b.charAt(j);
            if(x>='0'&&x<='9'&&y>='0'&&y<='9'){
                int ae=i,be=j;while(ae<a.length()&&a.charAt(ae)>='0'&&a.charAt(ae)<='9')ae++;while(be<b.length()&&b.charAt(be)>='0'&&b.charAt(be)<='9')be++;
                int az=i,bz=j;while(az<ae&&a.charAt(az)=='0')az++;while(bz<be&&b.charAt(bz)=='0')bz++;
                int length=Integer.compare(ae-az,be-bz);if(length!=0)return length;
                while(az<ae){int compare=Character.compare(a.charAt(az++),b.charAt(bz++));if(compare!=0)return compare;}
                i=ae;j=be;
            }else{int compare=Character.compare(x,y);if(compare!=0)return compare;i++;j++;}
        }
        return Integer.compare(a.length()-i,b.length()-j);
    }
}
