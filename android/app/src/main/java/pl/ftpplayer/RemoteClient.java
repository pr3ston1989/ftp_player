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
        HttpURLConnection connection=(HttpURLConnection)url(path).openConnection();
        connection.setRequestMethod(method); connection.setConnectTimeout(15000); connection.setReadTimeout(40000);
        connection.setInstanceFollowRedirects(false); connection.setRequestProperty("Accept-Encoding","identity");
        if (!account.optString("username").isEmpty()) connection.setRequestProperty("Authorization", "Basic " +
            Base64.encodeToString((account.optString("username")+":"+account.optString("password")).getBytes("UTF-8"), Base64.NO_WRAP));
        if (start>0) connection.setRequestProperty("Range","bytes="+start+"-");
        int status=connection.getResponseCode();
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
                if (!FTPReply.isPositiveCompletion(client.getReplyCode())) throw new IOException("Brak dostępu do katalogu");
                if (files!=null) for (FTPFile file:files) {
                    String name=file.getName();
                    if (name.equals(".")||name.equals("..")||file.isSymbolicLink()||name.contains("/")) continue;
                    rows.add(entry(path,name,file.isDirectory(),file.getSize()));
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
                    rows.add(entry(path,name,folder,0));
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
    long size(JSONObject entry) throws Exception {
        if(entry.optLong("size")>0) return entry.optLong("size");
        if(ftpMode()) { FTPClient client=ftp(); try { FTPFile file=client.mlistFile(remotePath(entry.getString("path"))); if(file!=null) return file.getSize();
            FTPFile[] files=client.listFiles(remotePath(entry.getString("path"))); return files.length>0?files[0].getSize():0;
        } finally { client.disconnect(); } }
        HttpURLConnection connection=http("HEAD",entry.getString("path"),0);
        try { return Math.max(0,connection.getContentLengthLong()); } finally { connection.disconnect(); }
    }
    InputStream read(JSONObject entry,long start) throws Exception {
        String path=entry.getString("path");
        if(ftpMode()) {
            FTPClient client=ftp(); client.setRestartOffset(start);
            InputStream stream;
            try { stream=client.retrieveFileStream(remotePath(path)); if(stream==null) throw new IOException("Nie można odczytać pliku"); }
            catch(Exception error) { client.disconnect(); throw error; }
            return new FilterInputStream(stream) { @Override public void close() throws IOException { try { super.close(); } finally { client.disconnect(); } } };
        }
        HttpURLConnection connection=http("GET",path,start);
        InputStream stream=connection.getInputStream();
        try {
            if(start>0 && connection.getResponseCode()==206 && !connection.getHeaderField("Content-Range").startsWith("bytes "+start+"-")) throw new IOException("Nieprawidłowy zakres");
            if(start>0 && connection.getResponseCode()!=206) {
                long remaining=start; byte[] buffer=new byte[65536];
                while(remaining>0) { int n=stream.read(buffer,0,(int)Math.min(remaining,buffer.length)); if(n<0) throw new EOFException(); remaining-=n; }
            }
        } catch(Exception error) { stream.close(); connection.disconnect(); throw error; }
        return new FilterInputStream(stream) { @Override public void close() throws IOException { try { super.close(); } finally { connection.disconnect(); } } };
    }
}
