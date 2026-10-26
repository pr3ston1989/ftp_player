package pl.ftpplayer;
import fi.iki.elonen.NanoHTTPD;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Opaque local URLs keep credentials out of VLC and support real byte seeking. */
final class MediaServer extends NanoHTTPD {
    private final Map<String,Source> sources=new ConcurrentHashMap<>();
    private static final class Source {
        final RemoteClient client; final JSONObject entry; final boolean preview; long used; long nextRead;
        synchronized int allowance(int length) throws IOException {if(!preview)return length;if(used>=128L*1024*1024)throw new IOException("Thumbnail transfer limit");return (int)Math.min(length,128L*1024*1024-used);}
        synchronized void count(int length) throws IOException {if(preview&&length>0){used+=length;long now=android.os.SystemClock.elapsedRealtime();nextRead=Math.max(nextRead,now)+(length*1000L)/(4*1024*1024);try{Thread.sleep(Math.max(0,nextRead-now));}catch(InterruptedException error){Thread.currentThread().interrupt();throw new InterruptedIOException();}}}
        Source(RemoteClient client, JSONObject entry,boolean preview) { this.client=client; this.entry=entry; this.preview=preview; }
    }
    MediaServer() { super("127.0.0.1",0); }
    String register(JSONObject account,JSONObject entry) throws Exception {
        return register(account,entry,false);
    }
    String register(JSONObject account,JSONObject entry,boolean preview) throws Exception {
        String token=UUID.randomUUID().toString();
        sources.put(token,new Source(new RemoteClient(account),entry,preview));
        return "http://127.0.0.1:"+getListeningPort()+"/"+token+"/"+android.net.Uri.encode(entry.getString("name"));
    }
    long previewUsage(String url){String[] parts=java.net.URI.create(url).getPath().split("/");Source source=sources.get(parts[1]);return source==null?0:source.used;}
    void unregister(String url){if(url!=null){String[] parts=java.net.URI.create(url).getPath().split("/");sources.remove(parts[1]);}}
    @Override public Response serve(IHTTPSession session) {
        String[] parts=session.getUri().split("/");
        Source source=parts.length>1?sources.get(parts[1]):null;
        if(source==null) return newFixedLengthResponse(Response.Status.NOT_FOUND,"text/plain", "Not found");
        try {
            long size=source.client.size(source.entry), start=0, end=size-1;
            source.entry.put("size",size);
            String range=session.getHeaders().get("range"); boolean partial=range!=null;
            if(partial) {
                java.util.regex.Matcher match=java.util.regex.Pattern.compile("bytes=(\\d*)-(\\d*)").matcher(range);
                if(!match.matches()||size<=0||(match.group(1).isEmpty()&&match.group(2).isEmpty())) return invalid(size);
                if(match.group(1).isEmpty()) { long suffix=Long.parseLong(match.group(2)); if(suffix==0) return invalid(size); start=Math.max(0,size-suffix); }
                else { start=Long.parseLong(match.group(1)); if(!match.group(2).isEmpty()) end=Math.min(end,Long.parseLong(match.group(2))); }
                if(start>=size||end<start) return invalid(size);
            }
            String mime=android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(source.entry.optString("name").replaceAll(".*\\.", "").toLowerCase(Locale.ROOT));
            if(mime==null) mime="application/octet-stream";
            long length=Math.max(0,end-start+1);
            InputStream stream=session.getMethod()==Method.HEAD||size==0?new ByteArrayInputStream(new byte[0]):source.client.read(source.entry,start,source.preview);
            final long[] remaining={length};
            InputStream limited=new FilterInputStream(stream) {
                @Override public int read(byte[] b,int off,int len) throws IOException {
                    if(remaining[0]<=0) return -1;
                    int n; synchronized(source){n=super.read(b,off,source.allowance((int)Math.min(len,remaining[0])));source.count(n);} if(n>0) remaining[0]-=n; return n;
                }
                @Override public int read() throws IOException { if(remaining[0]<=0) return -1; int n; synchronized(source){source.allowance(1);n=super.read();source.count(n>=0?1:0);} if(n>=0) remaining[0]--; return n; }
            };
            Response response=newFixedLengthResponse(partial?Response.Status.PARTIAL_CONTENT:Response.Status.OK,mime,limited,length);
            response.addHeader("Accept-Ranges","bytes"); response.addHeader("Cache-Control","no-store");
            if(source.preview){response.addHeader("Connection","close");response.setKeepAlive(false);}
            if(partial) response.addHeader("Content-Range","bytes "+start+"-"+end+"/"+size);
            return response;
        } catch(Exception error) { return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,"text/plain","Nie można odczytać pliku z serwera"); }
    }
    private Response invalid(long size) {
        Response response=newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE,"text/plain","");
        response.addHeader("Content-Range","bytes */"+size); return response;
    }
}
