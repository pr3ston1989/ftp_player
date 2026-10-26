package pl.ftpplayer;

import org.apache.commons.net.ftp.*;
import org.json.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import android.util.Base64;
import java.io.*;
import java.net.*;
import java.util.*;
import javax.net.ssl.*;
import java.security.KeyStore;

/** Read-only FTP, explicit FTPS and same-origin HTTPS autoindex transport. */
final class RemoteClient {
    static final class UnavailableException extends IOException { UnavailableException(){super("Plik lub folder jest niedostępny");} }
    final JSONObject account;
    final URI base;
    RemoteClient(JSONObject account) throws Exception {
        this.account = account;
        String address = account.getString("address").trim();
        base = new URI(address.endsWith("/") ? address : address + "/");
        if (!Arrays.asList("http","https","ftp","ftps").contains(base.getScheme()) || base.getHost()==null ||
            base.getUserInfo()!=null || base.getQuery()!=null || base.getFragment()!=null)
            throw new IOException("Podaj pełny adres https://, ftps:// lub ftp://. Login wpisz osobno.");
    }
    boolean ftpMode() { return base.getScheme().startsWith("ftp"); }
    static String safe(String path) throws IOException {
        if (path.indexOf('\r')>=0 || path.indexOf('\n')>=0 || path.indexOf('\0')>=0 || path.contains("\\")) throw new IOException("Nieprawidłowa ścieżka");
        for (String part:path.split("/")) if (part.equals("..")) throw new IOException("Nieprawidłowa ścieżka");
        return path.startsWith("/") ? path : "/"+path;
    }
    String remotePath(String path) throws IOException {
        String root = base.getPath();
        return (root.endsWith("/") ? root.substring(0,root.length()-1) : root) + safe(path);
    }
    URL url(String path) throws Exception {
        String raw = remotePath(path);
        return new URI(base.getScheme(), null, base.getHost(), base.getPort(), raw, null, null).toURL();
    }
    FTPClient ftp() throws Exception {
        FTPClient client;
        if (base.getScheme().equals("ftps")) {
            FTPSClient tls = new FTPSClient(false);
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore)null);
            for (TrustManager manager:factory.getTrustManagers()) if (manager instanceof X509TrustManager) tls.setTrustManager((X509TrustManager)manager);
            tls.setEndpointCheckingEnabled(true); client=tls;
        } else client=new FTPClient();
        client.setConnectTimeout(15000); client.setDefaultTimeout(40000); client.setControlEncoding("UTF-8");
        try {
            client.connect(base.getHost(), base.getPort()>0 ? base.getPort():21);
            String username=account.optString("username");
            if (!client.login(username.isEmpty()?"anonymous":username, account.optString("password")))
                throw new IOException("Serwer odrzucił dane logowania");
            if (client instanceof FTPSClient) { ((FTPSClient)client).execPBSZ(0); ((FTPSClient)client).execPROT("P"); }
            client.enterLocalPassiveMode(); client.setUseEPSVwithIPv4(true); client.setFileType(FTP.BINARY_FILE_TYPE);
            client.setBufferSize(262144); client.setDataTimeout(40000);
            return client;
        } catch(Exception error) { try { client.disconnect(); } catch(Exception ignored) {} throw error; }
    }
    HttpURLConnection http(String method, String path, long start) throws Exception {
        return http(method,path,start,false);
    }
    HttpURLConnection http(String method,String path,long start,boolean range) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)url(path).openConnection();
        connection.setRequestMethod(method); connection.setConnectTimeout(15000); connection.setReadTimeout(40000);
        connection.setInstanceFollowRedirects(false); connection.setRequestProperty("Accept-Encoding","identity");
        if (!account.optString("username").isEmpty()) connection.setRequestProperty("Authorization", "Basic " +
            Base64.encodeToString((account.optString("username")+":"+account.optString("password")).getBytes("UTF-8"), Base64.NO_WRAP));
        if (start>0||range) connection.setRequestProperty("Range","bytes="+start+"-");
        int status=connection.getResponseCode();
        if(status==404||status==410){connection.disconnect();throw new UnavailableException();}
        if (status<200 || status>=300) { connection.disconnect(); throw new IOException("Serwer HTTP: "+status+". Sprawdź adres, login i listę katalogów."); }
        return connection;
    }
    JSONArray list(String path) throws Exception {
        safe(path); ArrayList<JSONObject> rows=new ArrayList<>();
        if (ftpMode()) {
            FTPClient client=ftp();
            try {
                FTPFile[] files=client.mlistDir(remotePath(path));
                if (files==null || !FTPReply.isPositiveCompletion(client.getReplyCode())) files=client.listFiles(remotePath(path));
                if (!FTPReply.isPositiveCompletion(client.getReplyCode())) { if(client.getReplyCode()==550)throw new UnavailableException();throw new IOException("Brak dostępu do katalogu"); }
                if (files!=null) for (FTPFile file:files) {
                    String name=file.getName();
                    if (name.equals(".")||name.equals("..")||file.isSymbolicLink()||name.contains("/")) continue;
                    rows.add(entry(path,name,file.isDirectory(),file.getSize()).put("modified",file.getTimestamp()==null?0:file.getTimestamp().getTimeInMillis()).put("metadataKnown",true).put("sizeExact",true));
                }
            } finally { client.disconnect(); }
        } else {
            String directory=path.endsWith("/")?path:path+"/";
            HttpURLConnection connection=http("GET",directory,0);
            try (InputStream stream=connection.getInputStream()) {
                ByteArrayOutputStream bytes=new ByteArrayOutputStream(); byte[] buffer=new byte[65536]; int n;
                while((n=stream.read(buffer))>=0) { bytes.write(buffer,0,n); if(bytes.size()>4194304) throw new IOException("Za duża lista katalogu"); }
                URL current=url(directory); URI currentUri=current.toURI(); Set<String> seen=new HashSet<>();
                for(Element link:Jsoup.parse(bytes.toString("UTF-8"),current.toString()).select("a[href]")) {
                    URI uri;
                    try { uri=currentUri.resolve(link.attr("href")); } catch(Exception ignored) { continue; }
                    if(!Objects.equals(uri.getScheme(),currentUri.getScheme()) || !Objects.equals(uri.getAuthority(),currentUri.getAuthority()) || uri.getQuery()!=null || uri.getFragment()!=null) continue;
                    String decoded=uri.getPath(), prefix=currentUri.getPath();
                    if(decoded==null || !decoded.startsWith(prefix)) continue;
                    String name=decoded.substring(prefix.length()); boolean folder=name.endsWith("/");
                    if(folder) name=name.substring(0,name.length()-1);
                    if(name.isEmpty()||name.contains("/")||name.equals("..")||!seen.add(name)) continue;
                    String details="";
                    if(link.parent()!=null&&link.parent().parent()!=null&&link.parent().parent().tagName().equals("tr"))details=link.parent().parent().text();
                    else for(org.jsoup.nodes.Node node=link.nextSibling();node!=null;node=node.nextSibling()) {
                        if(node instanceof Element&&((Element)node).tagName().equals("a"))break;
                        details+=" "+(node instanceof org.jsoup.nodes.TextNode?((org.jsoup.nodes.TextNode)node).text():node instanceof Element?((Element)node).text():"");
                    }
                    long[] metadata=indexMetadata(details);
                    rows.add(entry(path,name,folder,metadata[0]).put("modified",metadata[1]).put("metadataKnown",metadata[2]>0));
                }
            } finally { connection.disconnect(); }
        }
        Collections.sort(rows,(a,b)-> { int d=Boolean.compare(b.optBoolean("directory"),a.optBoolean("directory")); return d!=0?d:a.optString("name").compareToIgnoreCase(b.optString("name")); });
        return new JSONArray(rows);
    }
    JSONObject entry(String parent,String name,boolean folder,long size) throws Exception {
        String path=(parent.endsWith("/")?parent:parent+"/")+name;
        String id=account.getString("id")+":"+path;
        return new JSONObject().put("id",id).put("name",name).put("path",safe(path)).put("directory",folder).put("size",size);
    }
    static long[] indexMetadata(String text) {
        long date=0,size=0; boolean known=false;
        java.util.regex.Matcher stamp=java.util.regex.Pattern.compile("(\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}(?::\\d{2})?|\\d{2}-[A-Za-z]{3}-\\d{4} \\d{2}:\\d{2})").matcher(text);
        String tail="";
        if(stamp.find()) {
            for(String pattern:new String[]{"yyyy-MM-dd HH:mm:ss","yyyy-MM-dd HH:mm","dd-MMM-yyyy HH:mm"}) {
                try { java.text.SimpleDateFormat parser=new java.text.SimpleDateFormat(pattern,Locale.US);parser.setLenient(false);parser.setTimeZone(TimeZone.getTimeZone("UTC"));date=parser.parse(stamp.group().replace('T',' ')).getTime();break; }catch(Exception ignored){}
            }
            tail=text.substring(stamp.end());
        }
        java.util.regex.Matcher value=java.util.regex.Pattern.compile("(?<![A-Za-z0-9])(\\d+(?:[.,]\\d+)?)\\s*([KMGTP]?)(?:i?B)?(?:\\s|$)",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(tail);
        if(value.find()){String unit=value.group(2).toUpperCase(Locale.ROOT);int power=unit.isEmpty()?0:" KMGTP".indexOf(unit);size=(long)(Double.parseDouble(value.group(1).replace(',','.'))*Math.pow(1024,power));known=true;}
        return new long[]{size,date,known?1:0};
    }
    JSONObject stat(JSONObject entry) throws Exception {
        JSONObject result=new JSONObject(entry.toString());String path=entry.getString("path");
        if(ftpMode()) {
            FTPClient client=ftp();try { FTPFile file=client.mlistFile(remotePath(path));if(file==null){FTPFile[] files=client.listFiles(remotePath(path));if(files.length>0)file=files[0];}if(file==null)throw new UnavailableException();result.put("size",file.getSize());result.put("modified",file.getTimestamp()==null?0:file.getTimestamp().getTimeInMillis()); }finally{client.disconnect();}
        }else{
            HttpURLConnection connection=http("HEAD",path,0);try {result.put("size",Math.max(0,connection.getContentLengthLong()));result.put("modified",connection.getLastModified());}finally{connection.disconnect();}
        }
        result.put("metadataKnown",true);result.put("sizeExact",true);return result;
    }
    long size(JSONObject entry) throws Exception {
        if(!ftpMode()&&!entry.optBoolean("sizeExact")){JSONObject fresh=stat(entry);entry.put("size",fresh.optLong("size")).put("modified",fresh.optLong("modified")).put("sizeExact",true);}
        if(entry.optBoolean("sizeExact")||entry.optLong("size")>0) return entry.optLong("size");
        if(ftpMode()) { FTPClient client=ftp(); try { FTPFile file=client.mlistFile(remotePath(entry.getString("path"))); if(file!=null) return file.getSize();
            FTPFile[] files=client.listFiles(remotePath(entry.getString("path"))); return files.length>0?files[0].getSize():0;
        } finally { client.disconnect(); } }
        HttpURLConnection connection=http("HEAD",entry.getString("path"),0);
        try { return Math.max(0,connection.getContentLengthLong()); } finally { connection.disconnect(); }
    }
    InputStream read(JSONObject entry,long start) throws Exception {
        return read(entry,start,false);
    }
    InputStream read(JSONObject entry,long start,boolean requireRange) throws Exception {
        String path=entry.getString("path");
        if(ftpMode()) {
            FTPClient client=ftp(); client.setRestartOffset(start);
            InputStream stream;
            try { stream=client.retrieveFileStream(remotePath(path)); if(stream==null) throw new IOException("Nie można odczytać pliku"); }
            catch(Exception error) { client.disconnect(); throw error; }
            return new FilterInputStream(stream) { @Override public void close() throws IOException { try { super.close(); } finally { client.disconnect(); } } };
        }
        HttpURLConnection connection=http("GET",path,start,requireRange);
        InputStream stream=connection.getInputStream();
        try {
            if(requireRange&&connection.getResponseCode()!=206)throw new IOException("Miniatury wymagają HTTP Range");
            if((start>0||requireRange) && connection.getResponseCode()==206 && !connection.getHeaderField("Content-Range").startsWith("bytes "+start+"-")) throw new IOException("Nieprawidłowy zakres");
            if(start>0 && connection.getResponseCode()!=206) {
                long remaining=start; byte[] buffer=new byte[65536];
                while(remaining>0) { int n=stream.read(buffer,0,(int)Math.min(remaining,buffer.length)); if(n<0) throw new EOFException(); remaining-=n; }
            }
        } catch(Exception error) { stream.close(); connection.disconnect(); throw error; }
        return new FilterInputStream(stream) { @Override public void close() throws IOException { try { super.close(); } finally { connection.disconnect(); } } };
    }
}
