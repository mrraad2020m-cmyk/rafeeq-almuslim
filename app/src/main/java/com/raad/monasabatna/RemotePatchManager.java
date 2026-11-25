package com.raad.monasabatna;

import android.content.Context;
import android.webkit.WebResourceResponse;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Downloads published content-only deltas and exposes actionable status for troubleshooting. */
public final class RemotePatchManager {
    private static final String BASE="https://sonkrnhnzfygdeawfmoy.supabase.co";
    private static final String KEY="sb_publishable_gbv6qgNC32m3g6czdRSFEg_F7rwu8cH";
    private static final int MAX_ENTRIES=1500;
    private static final long MAX_TOTAL_UNCOMPRESSED=64L*1024*1024;
    private static final long MAX_ENTRY_UNCOMPRESSED=24L*1024*1024;
    public interface ResultCallback { void onResult(boolean updated, String message); }
    private final Context context;
    private final File root;

    public RemotePatchManager(Context c){context=c.getApplicationContext();root=new File(context.getFilesDir(),"remote_content");root.mkdirs();}

    public void applyLatestAsync(ResultCallback callback){
        new Thread(()->{
            boolean updated=false; String message;
            try {
                int count=applyLatest(); updated=count>0;
                android.content.SharedPreferences p=context.getSharedPreferences("remote_patch",0);
                long bytes=p.getLong("last_downloaded_bytes",0);
                String version=p.getString("last_applied_version","");
                message=updated?"تم تحديث البيانات بنجاح ✅"
                    :"لا توجد تحديثات جديدة. آخر إصدار مطبق: "+p.getString("version","غير معروف");
            }
            catch(Exception e){
                message="فشل التحديث: "+safeMessage(e);
                context.getSharedPreferences("remote_patch",0).edit().putString("last_error",message).putLong("last_check",System.currentTimeMillis()).apply();
                android.util.Log.e("RemotePatchManager",message,e);
            }
            if(callback!=null) callback.onResult(updated,message);
        }).start();
    }

    private int applyLatest() throws Exception {
        android.content.SharedPreferences prefs=context.getSharedPreferences("remote_patch",0);
        String lastTime=prefs.getString("created_at","");
        boolean replayForRendererFix=!prefs.getBoolean("asset_loader_v1_applied",false);
        String query=BASE+"/rest/v1/remote_app_releases?select=version,delta_path,deleted_files,created_at,manifest&status=eq.published&order=created_at.asc&limit=1000";
        if(!replayForRendererFix&&!lastTime.isEmpty()) query+="&created_at=gt."+URLEncoder.encode(lastTime,"UTF-8");
        JSONArray rows=new JSONArray(get(query));
        if(rows.length()==0){prefs.edit().putBoolean("asset_loader_v1_applied",true).putString("last_error","").putLong("last_check",System.currentTimeMillis()).apply();return 0;}
        int applied=0; List<String> failures=new ArrayList<>();
        long downloadedBytes=0; String lastProcessedVersion="";
        for(int r=0;r<rows.length();r++){
            JSONObject release=rows.getJSONObject(r); String version=release.optString("version","");
            File temp=new File(context.getCacheDir(),"content_delta_"+r+".zip");
            File stage=new File(context.getFilesDir(),"remote_stage_"+r);
            try {
                String path=release.getString("delta_path");
                String url=BASE+"/storage/v1/object/public/app-updates/"+path;
                download(url,temp);
                downloadedBytes += temp.length();
                JSONObject manifest=release.optJSONObject("manifest");
                if(manifest==null) throw new IOException("بيانات manifest غير موجودة للإصدار "+version);
                deleteTree(stage); if(!stage.mkdirs()&&!stage.isDirectory())throw new IOException("تعذر إنشاء مجلد التحديث");
                Set<String> seen=new HashSet<>(); long total=0; int entries=0;
                try(ZipInputStream in=new ZipInputStream(new BufferedInputStream(new FileInputStream(temp)))){
                    ZipEntry e; byte[] b=new byte[16384];
                    while((e=in.getNextEntry())!=null){
                        if(++entries>MAX_ENTRIES)throw new IOException("عدد ملفات ZIP أكبر من المسموح");
                        String name=e.getName().replace('\\','/');
                        if(e.isDirectory())continue;
                        if(name.startsWith("/")||name.matches("^[A-Za-z]:.*")||name.contains("../")||name.equals("..")||name.contains("//"))throw new IOException("مسار غير آمن داخل ZIP");
                        if(!seen.add(name))throw new IOException("اسم ملف مكرر داخل ZIP: "+name);
                        if(protectedPath(name))continue;
                        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); int n; long fileSize=0;
                        while((n=in.read(b))>0){fileSize+=n;total+=n;if(fileSize>MAX_ENTRY_UNCOMPRESSED||total>MAX_TOTAL_UNCOMPRESSED)throw new IOException("حجم ZIP بعد فك الضغط أكبر من المسموح");bytes.write(b,0,n);}
                        byte[] content=bytes.toByteArray(); String expected=manifest.optString(name,"");
                        if(expected.isEmpty()||!expected.equals(sha256(content)))throw new IOException("فشل التحقق من سلامة الملف: "+name);
                        File out=new File(stage,name); String stagePath=stage.getCanonicalPath()+File.separator;
                        if(!out.getCanonicalPath().startsWith(stagePath))throw new IOException("مسار ملف غير آمن");
                        File parent=out.getParentFile();if(parent!=null)parent.mkdirs();
                        try(FileOutputStream f=new FileOutputStream(out)){f.write(content);}
                    }
                }
                JSONArray deleted=release.optJSONArray("deleted_files");
                if(deleted!=null)for(int i=0;i<deleted.length();i++){String name=deleted.optString(i).replace('\\','/');if(!name.startsWith("/")&&!name.contains("../")&&!protectedPath(name))deleteTree(new File(root,name));}
                copyTree(stage,root);
                // Advance the checkpoint only after the entire release has been verified and copied.
                prefs.edit().putString("version",version).putString("created_at",release.getString("created_at")).commit();
                applied++; lastProcessedVersion=version;
            } catch(Exception ex){
                String failure="الإصدار "+version+": "+safeMessage(ex);failures.add(failure);android.util.Log.e("RemotePatchManager",failure,ex);
                // Releases are deltas and may depend on earlier releases. Never skip a failed one.
                break;
            } finally {deleteTree(stage);if(temp.exists())temp.delete();}
        }
        String error=joinFailures(failures);
        prefs.edit().putBoolean("asset_loader_v1_applied",failures.isEmpty()).putString("last_error",error)
            .putLong("last_check",System.currentTimeMillis()).putInt("last_applied_count",applied)
            .putLong("last_downloaded_bytes",downloadedBytes).putString("last_applied_version",lastProcessedVersion).commit();
        if(!failures.isEmpty()) throw new IOException("تم تطبيق "+applied+" تحديثات قبل التوقف. "+error);
        return applied;
    }

    private String joinFailures(List<String> values){StringBuilder b=new StringBuilder();for(String v:values){if(b.length()>0)b.append(" | ");b.append(v);}return b.toString();}
    private String safeMessage(Exception e){String m=e.getMessage();if(m==null||m.trim().isEmpty())m=e.getClass().getSimpleName();return m.length()>220?m.substring(0,220):m;}
    private boolean protectedPath(String name){String p=name.toLowerCase(Locale.ROOT).replace('\\','/');if(p.contains("/quran/")||p.startsWith("quran/"))return true;String n=p.substring(p.lastIndexOf('/')+1);return n.startsWith("adhan")||p.contains("/adhan/")||n.contains("adhan_");}
    private String sha256(byte[] data)throws Exception{byte[] h=java.security.MessageDigest.getInstance("SHA-256").digest(data);StringBuilder s=new StringBuilder();for(byte x:h)s.append(String.format(Locale.US,"%02x",x));return s.toString();}
    private String get(String url)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setRequestProperty("apikey",KEY);c.setRequestProperty("Accept","application/json");try{int code=c.getResponseCode();if(code<200||code>=300)throw new IOException("Supabase HTTP "+code+": "+readError(c));return read(c.getInputStream());}finally{c.disconnect();}}
    private void download(String url,File f)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(20000);c.setReadTimeout(90000);c.setRequestProperty("apikey",KEY);try{int code=c.getResponseCode();if(code<200||code>=300)throw new IOException("تنزيل ZIP فشل HTTP "+code+": "+readError(c));try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(f)){byte[] b=new byte[32768];int n;while((n=in.read(b))>0)out.write(b,0,n);}}finally{c.disconnect();}}
    private String readError(HttpURLConnection c){try(InputStream in=c.getErrorStream()){return in==null?"":read(in);}catch(Exception e){return "";}}
    private String read(InputStream in)throws Exception{try(InputStream x=in;ByteArrayOutputStream o=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=x.read(b))>0)o.write(b,0,n);return o.toString("UTF-8");}}
    private void copyTree(File from,File to)throws Exception{File[] fs=from.listFiles();if(fs==null)return;for(File f:fs){File d=new File(to,f.getName());if(f.isDirectory()){d.mkdirs();copyTree(f,d);}else{File parent=d.getParentFile();if(parent!=null)parent.mkdirs();File tmp=new File(d.getPath()+".new");try(InputStream in=new FileInputStream(f);OutputStream out=new FileOutputStream(tmp)){byte[] b=new byte[16384];int n;while((n=in.read(b))>0)out.write(b,0,n);}if(d.exists()&&!d.delete())throw new IOException("تعذر استبدال الملف "+d.getName());if(!tmp.renameTo(d))throw new IOException("تعذر اعتماد الملف "+d.getName());}}}
    private void deleteTree(File f){if(f.isDirectory()){File[] c=f.listFiles();if(c!=null)for(File x:c)deleteTree(x);}if(f.exists())f.delete();}

    // Work on the response only: never rewrite the downloaded index or change update priority.
    private WebResourceResponse remoteIndexResponse(File f, String assetPrefix) throws IOException {
        try {
            byte[] bytes;
            try (InputStream in = new BufferedInputStream(new FileInputStream(f));
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                long total = 0;
                while ((n = in.read(buffer)) != -1) {
                    total += n;
                    if (total > MAX_ENTRY_UNCOMPRESSED) throw new IOException("Remote index is too large");
                    out.write(buffer, 0, n);
                }
                bytes = out.toByteArray();
            }
            // Reject malformed UTF-8 rather than silently corrupting the user's existing content.
            String html = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
            String result = addInterfaceAssets(html, assetPrefix);
            return new WebResourceResponse("text/html", "UTF-8",
                new ByteArrayInputStream(result.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // Preserve the previous response if the remote document cannot safely be processed.
            android.util.Log.w("RemotePatchManager", "Serving remote index unchanged", e);
            return new WebResourceResponse("text/html", null, new FileInputStream(f));
        }
    }

    private static final class AssetReference {
        final String value, baseHref;
        AssetReference(String value, String baseHref) { this.value = value; this.baseHref = baseHref; }
    }

    private String addInterfaceAssets(String html, String assetPrefix) {
        List<AssetReference> styles = new ArrayList<>(), scripts = new ArrayList<>();
        String baseHref = null;
        boolean inHead = false, sawHead = false;
        int headEnd = -1, templateDepth = 0, pos = 0;
        while (pos < html.length()) {
            int start = html.indexOf('<', pos);
            if (start < 0) break;
            if (html.startsWith("<!--", start)) {
                int end = html.indexOf("-->", start + 4);
                if (end < 0) break;
                pos = end + 3;
                continue;
            }
            int end = htmlTagEnd(html, start + 1);
            if (end < 0) break;
            int i = start + 1;
            boolean closing = i < end && html.charAt(i) == '/';
            if (closing) i++;
            int nameStart = i;
            while (i < end && isTagNameChar(html.charAt(i))) i++;
            if (i == nameStart) { pos = end + 1; continue; }
            String name = html.substring(nameStart, i).toLowerCase(Locale.ROOT);
            if (closing && name.equals("template") && templateDepth > 0) templateDepth--;
            else if (!closing && name.equals("template")) templateDepth++;
            if (templateDepth == 0) {
                if (!closing && name.equals("head") && !sawHead) {
                    sawHead = true;
                    inHead = true;
                } else if (closing && name.equals("head") && inHead) {
                    headEnd = start;
                    inHead = false;
                } else if (!closing) {
                    Map<String,String> attrs = htmlAttributes(html, i, end);
                    if (inHead && baseHref == null && name.equals("base") && attrs.containsKey("href"))
                        baseHref = attrs.get("href");
                    if (name.equals("link") && attrs.containsKey("href") && hasRelStylesheet(attrs.get("rel")))
                        styles.add(new AssetReference(attrs.get("href"), baseHref));
                    if (name.equals("script") && attrs.containsKey("src"))
                        scripts.add(new AssetReference(attrs.get("src"), baseHref));
                }
            }
            pos = end + 1;
            // Ignore tag-like strings inside raw-text elements, including commented examples.
            if (!closing && (name.equals("script") || name.equals("style") || name.equals("title")
                    || name.equals("textarea") || name.equals("noscript"))) {
                int rawEnd = rawTextEnd(html, pos, name);
                if (rawEnd < 0) break;
                pos = rawEnd;
            }
        }
        if (headEnd < 0) {
            android.util.Log.w("RemotePatchManager", "Remote index has no complete head; serving it unchanged");
            return html;
        }
        URI document = URI.create(assetPrefix + "index.html");
        boolean hasStyle = referencesAsset(styles, document, assetPrefix + "modern-ui.css");
        boolean hasScript = referencesAsset(scripts, document, assetPrefix + "interface-settings.js");
        if (hasStyle && hasScript) return html;
        // Append after the existing styles and CSP meta tags, but before the body is parsed.
        // Absolute local URLs are not redirected by an HTML <base> element.
        StringBuilder additions = new StringBuilder();
        if (!hasStyle) additions.append("\n<link rel=\"stylesheet\" href=\"")
            .append(assetPrefix).append("modern-ui.css\">\n");
        if (!hasScript) additions.append("\n<script src=\"")
            .append(assetPrefix).append("interface-settings.js\"></script>\n");
        return html.substring(0, headEnd) + additions + html.substring(headEnd);
    }

    private boolean referencesAsset(List<AssetReference> references, URI document, String target) {
        URI expected = URI.create(target);
        for (AssetReference ref : references) {
            URI base = document;
            // Resolve each URL against the first base present when its tag was parsed.
            try { if (ref.baseHref != null) base = document.resolve(new URI(decodeHtmlAttribute(ref.baseHref))); }
            catch (IllegalArgumentException | URISyntaxException ignored) { }
            try {
                URI uri = base.resolve(new URI(decodeHtmlAttribute(ref.value))).normalize();
                if (expected.getScheme().equalsIgnoreCase(uri.getScheme())
                        && Objects.equals(expected.getRawAuthority(), uri.getRawAuthority())
                        && expected.getPath().equals(uri.getPath())) return true;
            } catch (IllegalArgumentException | URISyntaxException ignored) { }
        }
        return false;
    }

    private boolean hasRelStylesheet(String rel) {
        if (rel == null) return false;
        for (String token : rel.split("\\s+")) if (token.equalsIgnoreCase("stylesheet")) return true;
        return false;
    }

    private int htmlTagEnd(String html, int from) {
        char quote = 0;
        for (int i = from; i < html.length(); i++) {
            char c = html.charAt(i);
            if (quote != 0) { if (c == quote) quote = 0; }
            else if (c == '\'' || c == '"') quote = c;
            else if (c == '>') return i;
        }
        return -1;
    }

    private boolean isTagNameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
            || (c >= '0' && c <= '9') || c == '-' || c == ':';
    }

    private boolean isHtmlSpace(char c) { return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f'; }

    private Map<String,String> htmlAttributes(String html, int from, int end) {
        Map<String,String> result = new HashMap<>();
        int i = from;
        while (i < end) {
            while (i < end && (isHtmlSpace(html.charAt(i)) || html.charAt(i) == '/')) i++;
            int start = i;
            while (i < end && !isHtmlSpace(html.charAt(i)) && html.charAt(i) != '=' && html.charAt(i) != '/') i++;
            if (i == start) { i++; continue; }
            String name = html.substring(start, i).toLowerCase(Locale.ROOT), value = "";
            while (i < end && isHtmlSpace(html.charAt(i))) i++;
            if (i < end && html.charAt(i) == '=') {
                i++;
                while (i < end && isHtmlSpace(html.charAt(i))) i++;
                if (i < end && (html.charAt(i) == '\'' || html.charAt(i) == '"')) {
                    char quote = html.charAt(i++);
                    int valueStart = i;
                    while (i < end && html.charAt(i) != quote) i++;
                    value = html.substring(valueStart, i);
                    if (i < end) i++;
                } else {
                    int valueStart = i;
                    while (i < end && !isHtmlSpace(html.charAt(i))) i++;
                    value = html.substring(valueStart, i);
                }
            }
            // As in HTML parsing, the first occurrence of a duplicate attribute wins.
            if (!result.containsKey(name)) result.put(name, value);
        }
        return result;
    }

    private int rawTextEnd(String html, int from, String name) {
        for (int i = html.indexOf('<', from); i >= 0; i = html.indexOf('<', i + 1)) {
            int after = i + 2 + name.length();
            if (i + 1 < html.length() && html.charAt(i + 1) == '/'
                    && after < html.length() && html.regionMatches(true, i + 2, name, 0, name.length())
                    && (isHtmlSpace(html.charAt(after)) || html.charAt(after) == '>' || html.charAt(after) == '/'))
                return i;
        }
        return -1;
    }

    private String decodeHtmlAttribute(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '&') {
                int semi = value.indexOf(';', i + 1);
                if (semi > i && semi - i <= 12) {
                    String entity = value.substring(i + 1, semi);
                    int code = -1;
                    try {
                        if (entity.startsWith("#x") || entity.startsWith("#X")) code = Integer.parseInt(entity.substring(2), 16);
                        else if (entity.startsWith("#")) code = Integer.parseInt(entity.substring(1));
                        else if (entity.equals("amp")) code = '&';
                        else if (entity.equals("quot")) code = '"';
                        else if (entity.equals("apos")) code = '\'';
                        else if (entity.equals("period")) code = '.';
                        else if (entity.equals("sol")) code = '/';
                        else if (entity.equals("colon")) code = ':';
                    } catch (NumberFormatException ignored) { }
                    if (Character.isValidCodePoint(code) && !(code >= 0xd800 && code <= 0xdfff)) {
                        result.appendCodePoint(code); i = semi; continue;
                    }
                }
            }
            result.append(c);
        }
        return result.toString();
    }

    public WebResourceResponse intercept(String url){
        try {
            int cut=url.indexOf('?'); if(cut<0)cut=url.indexOf('#'); if(cut>=0)url=url.substring(0,cut);
            final String prefix="https://appassets.androidplatform.net/assets/";
            final String legacyPrefix="file:///android_asset/"; final String rel, assetPrefix;
            if(url.startsWith(prefix)){assetPrefix=prefix;rel=URLDecoder.decode(url.substring(prefix.length()),"UTF-8");}
            else if(url.startsWith(legacyPrefix)){assetPrefix=legacyPrefix;rel=URLDecoder.decode(url.substring(legacyPrefix.length()),"UTF-8");}else return null;
            String normalized=rel.replace('\\','/');
            if(normalized.isEmpty()||normalized.startsWith("/")||normalized.contains("../")||normalized.equals("..")||protectedPath(normalized))return null;
            File f=new File(root,normalized);String rootPath=root.getCanonicalPath()+File.separator;
            if(!f.getCanonicalPath().startsWith(rootPath)||!f.isFile())return null;
            String mime="application/octet-stream",l=normalized.toLowerCase(Locale.ROOT);
            if(l.endsWith(".html")||l.endsWith(".htm"))mime="text/html";else if(l.endsWith(".js"))mime="application/javascript";else if(l.endsWith(".css"))mime="text/css";else if(l.endsWith(".json"))mime="application/json";else if(l.endsWith(".svg"))mime="image/svg+xml";else if(l.endsWith(".png"))mime="image/png";else if(l.endsWith(".jpg")||l.endsWith(".jpeg"))mime="image/jpeg";else if(l.endsWith(".webp"))mime="image/webp";else if(l.endsWith(".gif"))mime="image/gif";else if(l.endsWith(".woff"))mime="font/woff";else if(l.endsWith(".woff2"))mime="font/woff2";
            if(normalized.equals("index.html"))return remoteIndexResponse(f,assetPrefix);
            return new WebResourceResponse(mime,null,new FileInputStream(f));
        }catch(Exception e){android.util.Log.e("RemotePatchManager","Intercept failed",e);return null;}
    }
}
