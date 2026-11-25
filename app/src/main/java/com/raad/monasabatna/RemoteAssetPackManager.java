package com.raad.monasabatna;

import android.content.Context;
import android.webkit.WebResourceResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Downloads the separately published Quran/adhan files into app-private persistent storage. */
public final class RemoteAssetPackManager {
    private static final String RELEASE_API = "https://api.github.com/repos/mrraad2020m-cmyk/rafeeq-almuslim-assets/releases/latest";
    private static final String ASSET_PREFIX = "https://appassets.androidplatform.net/assets/";
    private static final String PREFS = "offline_asset_pack";
    private static final String READY_VERSION = "verified_release_tag";
    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 120000;
    private static final long MAX_MANIFEST_BYTES = 2L * 1024 * 1024;
    private static final long MAX_TOTAL_UNCOMPRESSED = 128L * 1024 * 1024;
    private static final long MAX_FILE_UNCOMPRESSED = 12L * 1024 * 1024;

    public interface Listener {
        void onProgress(int percent, String message);
        void onFinished(boolean success, String message);
    }

    private final Context context;
    private final File mediaRoot;
    private final File stagingRoot;
    private final File backupRoot;

    public RemoteAssetPackManager(Context context) {
        this.context = context.getApplicationContext();
        File files = this.context.getFilesDir();
        mediaRoot = new File(files, "rafeeq_media");
        stagingRoot = new File(files, "rafeeq_media_staging");
        backupRoot = new File(files, "rafeeq_media_backup");
    }

    /** True only when the downloaded pack was verified and all app-required files remain present. */
    public boolean isReady() {
        String tag = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(READY_VERSION, "");
        if (tag.isEmpty()) return false;
        File quran = new File(mediaRoot, "quran");
        File[] pages = quran.listFiles((dir, name) -> name.matches("page-[0-9]{3}\\.png"));
        if (pages == null || pages.length != 604) return false;
        for (int page = 1; page <= 604; page++) {
            if (!fileExists(String.format(Locale.US, "quran/page-%03d.png", page))) return false;
        }
        return fileExists("adhan/adhan_makkah.mp3")
                && fileExists("adhan/adhan_nasser_alqatami.mp3")
                && fileExists("adhan/salat_nabi.mp3");
    }

    public File getAudioFile(String fileName) {
        if (!("adhan_makkah.mp3".equals(fileName)
                || "adhan_nasser_alqatami.mp3".equals(fileName)
                || "salat_nabi.mp3".equals(fileName))) return null;
        File result = new File(new File(mediaRoot, "adhan"), fileName);
        return result.isFile() && result.length() > 0 ? result : null;
    }

    /** Intercepts Quran page URLs and serves verified private files, never adding them to MediaStore. */
    public WebResourceResponse intercept(String requestUrl) {
        try {
            if (requestUrl == null) return null;
            int cut = requestUrl.indexOf('?');
            if (cut >= 0) requestUrl = requestUrl.substring(0, cut);
            cut = requestUrl.indexOf('#');
            if (cut >= 0) requestUrl = requestUrl.substring(0, cut);
            if (!requestUrl.startsWith(ASSET_PREFIX)) return null;
            String relative = URLDecoder.decode(requestUrl.substring(ASSET_PREFIX.length()), "UTF-8")
                    .replace('\\', '/');
            if (!relative.matches("quran/page-[0-9]{3}\\.png")) return null;
            File page = new File(mediaRoot, relative);
            String rootPath = mediaRoot.getCanonicalPath() + File.separator;
            if (!page.getCanonicalPath().startsWith(rootPath)) return notFound();
            if (!page.isFile()) return notFound();
            return new WebResourceResponse("image/png", null, new BufferedInputStream(new FileInputStream(page)));
        } catch (Exception e) {
            android.util.Log.w("RemoteAssetPack", "Could not serve local Quran page", e);
            return notFound();
        }
    }

    private WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                Collections.<String, String>emptyMap(),
                new ByteArrayInputStream("Quran asset is not downloaded".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    public void downloadLatest(Listener listener) {
        new Thread(() -> {
            try {
                downloadAndInstall(listener);
                if (listener != null) listener.onFinished(true, "تم تنزيل ملفات القرآن والأذان والتحقق منها.");
            } catch (Exception e) {
                android.util.Log.e("RemoteAssetPack", "Asset download failed", e);
                if (listener != null) listener.onFinished(false,
                        "تعذر إكمال التنزيل أو التحقق من الملفات. تحقق من الاتصال والمساحة ثم أعد المحاولة.\n"
                                + safeMessage(e));
            }
        }, "RafeeqAssetDownload").start();
    }

    private void downloadAndInstall(Listener listener) throws Exception {
        if (isReady()) return;
        JSONObject release = getJson(RELEASE_API);
        String tag = release.optString("tag_name", "").trim();
        if (tag.isEmpty()) throw new IOException("لم يعثر GitHub على إصدار موارد منشور.");
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) throw new IOException("قائمة ملفات الإصدار غير موجودة.");
        JSONObject quran = findAsset(assets, "RafeeqAlMuslim-Quran-Assets-", ".zip");
        JSONObject adhan = findAsset(assets, "RafeeqAlMuslim-Adhan-Assets-", ".zip");
        JSONObject manifest = findAsset(assets, "RafeeqAlMuslim-Assets-Manifest-", ".zip");
        long expectedTotal = Math.max(1L, assetSize(quran) + assetSize(adhan) + assetSize(manifest));
        File manifestZip = new File(context.getCacheDir(), "rafeeq_asset_manifest.zip");
        File quranZip = new File(context.getCacheDir(), "rafeeq_quran_assets.zip");
        File adhanZip = new File(context.getCacheDir(), "rafeeq_adhan_assets.zip");
        deleteTree(manifestZip);
        deleteTree(quranZip);
        deleteTree(adhanZip);
        try {
            long completed = 0;
            progress(listener, 0, "جارٍ تنزيل قائمة التحقق…");
            completed = download(assetUrl(manifest), manifestZip, assetSize(manifest), completed, expectedTotal, listener);
            progress(listener, percent(completed, expectedTotal), "جارٍ تنزيل صفحات القرآن الكريم…");
            completed = download(assetUrl(quran), quranZip, assetSize(quran), completed, expectedTotal, listener);
            progress(listener, percent(completed, expectedTotal), "جارٍ تنزيل أصوات الأذان…");
            completed = download(assetUrl(adhan), adhanZip, assetSize(adhan), completed, expectedTotal, listener);

            progress(listener, 88, "اكتمل التنزيل؛ جارٍ التحقق من سلامة الملفات…");
            Map<String, String> checksums = readChecksums(manifestZip);
            validateRequiredChecksums(checksums);
            deleteTree(stagingRoot);
            if (!stagingRoot.mkdirs() && !stagingRoot.isDirectory())
                throw new IOException("تعذر إنشاء مجلد التخزين الآمن.");
            Set<String> extracted = new HashSet<>();
            long totalUncompressed = totalUncompressedSize(quranZip) + totalUncompressedSize(adhanZip);
            if (totalUncompressed > MAX_TOTAL_UNCOMPRESSED)
                throw new IOException("حجم الملفات بعد فك الضغط أكبر من الحد الآمن.");
            long extractedBytes = 0;
            extractedBytes = extractAndVerify(quranZip, stagingRoot, checksums, extracted,
                    extractedBytes, totalUncompressed, listener);
            extractAndVerify(adhanZip, stagingRoot, checksums, extracted,
                    extractedBytes, totalUncompressed, listener);
            validateCompleteSet(checksums, extracted);
            progress(listener, 99, "تم التحقق؛ جارٍ حفظ الملفات داخل مساحة التطبيق الخاصة…");
            replaceInstalledFiles();
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(READY_VERSION, tag).commit();
        } finally {
            deleteTree(manifestZip);
            deleteTree(quranZip);
            deleteTree(adhanZip);
            deleteTree(stagingRoot);
        }
    }

    private JSONObject getJson(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "RafeeqAlMuslim-Android");
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("GitHub API HTTP " + code);
            byte[] bytes = readBounded(connection.getInputStream(), 2L * 1024 * 1024);
            return new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        } finally { connection.disconnect(); }
    }

    private JSONObject findAsset(JSONArray assets, String prefix, String suffix) throws Exception {
        for (int i = 0; i < assets.length(); i++) {
            JSONObject item = assets.getJSONObject(i);
            String name = item.optString("name", "");
            if (name.startsWith(prefix) && name.endsWith(suffix)) {
                if (item.optString("state", "uploaded").equals("uploaded")
                        && !item.optString("browser_download_url", "").isEmpty()) return item;
            }
        }
        throw new IOException("ملف الإصدار المطلوب غير موجود: " + prefix);
    }

    private long assetSize(JSONObject asset) { return Math.max(0L, asset.optLong("size", 0L)); }
    private String assetUrl(JSONObject asset) throws IOException {
        String url = asset.optString("browser_download_url", "");
        if (!url.startsWith("https://github.com/")) throw new IOException("رابط تنزيل غير صالح.");
        return url;
    }

    private long download(String address, File destination, long expectedSize,
                          long completedBefore, long overallSize, Listener listener) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "RafeeqAlMuslim-Android");
        connection.setRequestProperty("Accept", "application/octet-stream");
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("فشل تنزيل إحدى الحزم (HTTP " + code + ").");
            long contentLength = connection.getContentLength();
            long total = contentLength > 0 ? contentLength : expectedSize;
            File parent = destination.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs())
                throw new IOException("تعذر إنشاء مجلد التنزيل المؤقت.");
            long read = 0;
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
                byte[] buffer = new byte[32768];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    output.write(buffer, 0, n);
                    read += n;
                    long estimate = total > 0 ? Math.min(read, total) : read;
                    int percent = 2 + (int) ((completedBefore + estimate) * 84L / overallSize);
                    progress(listener, Math.min(87, percent), "جارٍ تنزيل الملفات… " + formatMb(completedBefore + read)
                            + " / " + formatMb(overallSize) + " ميغابايت");
                }
            }
            if (expectedSize > 0 && destination.length() != expectedSize)
                throw new IOException("حجم الملف الذي وصل من GitHub غير مكتمل.");
            return completedBefore + destination.length();
        } finally { connection.disconnect(); }
    }

    private Map<String, String> readChecksums(File zipFile) throws Exception {
        Map<String, String> result = new HashMap<>();
        try (ZipFile zip = new ZipFile(zipFile)) {
            ZipEntry sums = zip.getEntry("SHA256SUMS.txt");
            if (sums == null || sums.getSize() > MAX_MANIFEST_BYTES)
                throw new IOException("قائمة SHA256SUMS.txt غير موجودة أو أكبر من المسموح.");
            ZipEntry readme = zip.getEntry("README_AR.txt");
            if (readme == null) throw new IOException("ملف README_AR.txt غير موجود في manifest.");
            byte[] bytes;
            try (InputStream input = zip.getInputStream(sums)) { bytes = readBounded(input, MAX_MANIFEST_BYTES); }
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            for (String line : text.split("\\r?\\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                java.util.regex.Matcher matcher = java.util.regex.Pattern
                        .compile("^([0-9a-fA-F]{64})\\s+[*]?(.+)$").matcher(trimmed);
                if (!matcher.matches()) throw new IOException("تنسيق غير معروف في manifest.");
                String path = matcher.group(2).trim().replace('\\', '/');
                if (!isAllowedMediaPath(path)) throw new IOException("مسار غير مسموح في manifest.");
                if (result.put(path, matcher.group(1).toLowerCase(Locale.ROOT)) != null)
                    throw new IOException("مسار مكرر في manifest.");
            }
        }
        if (result.isEmpty()) throw new IOException("manifest لا يحتوي على بصمات ملفات.");
        return result;
    }

    private boolean isAllowedMediaPath(String path) {
        return path.matches("quran/page-[0-9]{3}\\.png")
                || path.matches("adhan/[a-z0-9_-]+\\.mp3");
    }

    private void validateRequiredChecksums(Map<String, String> sums) throws IOException {
        for (int page = 1; page <= 604; page++) {
            String name = String.format(Locale.US, "quran/page-%03d.png", page);
            if (!sums.containsKey(name)) throw new IOException("manifest لا يتضمن " + name);
        }
        String[] audio = {"adhan/adhan_makkah.mp3", "adhan/adhan_nasser_alqatami.mp3", "adhan/salat_nabi.mp3"};
        for (String name : audio) if (!sums.containsKey(name))
            throw new IOException("manifest لا يتضمن ملف الصوت المطلوب.");
    }

    private long totalUncompressedSize(File zipFile) throws Exception {
        long total = 0;
        try (ZipFile zip = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                long size = entry.getSize();
                if (size < 0 || size > MAX_FILE_UNCOMPRESSED) throw new IOException("حجم عنصر ZIP غير صالح.");
                total += size;
                if (total > MAX_TOTAL_UNCOMPRESSED) throw new IOException("حجم ZIP بعد فك الضغط أكبر من الحد الآمن.");
            }
        }
        return total;
    }

    private long extractAndVerify(File zipFile, File destinationRoot, Map<String, String> sums,
                                  Set<String> extracted, long priorBytes, long totalBytes,
                                  Listener listener) throws Exception {
        long copied = priorBytes;
        try (ZipFile zip = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            byte[] buffer = new byte[32768];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                if (!isAllowedMediaPath(name) || !sums.containsKey(name))
                    throw new IOException("الحزمة تحتوي على ملف غير متوقع.");
                if (!extracted.add(name)) throw new IOException("اسم ملف مكرر داخل الحزم.");
                long size = entry.getSize();
                if (size < 0 || size > MAX_FILE_UNCOMPRESSED)
                    throw new IOException("حجم ملف غير صالح بعد فك الضغط.");
                File output = new File(destinationRoot, name);
                String rootPath = destinationRoot.getCanonicalPath() + File.separator;
                if (!output.getCanonicalPath().startsWith(rootPath)) throw new IOException("مسار ملف غير آمن.");
                File parent = output.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs())
                    throw new IOException("تعذر إنشاء مجلد أحد الموارد.");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long fileBytes = 0;
                try (InputStream input = new DigestInputStream(new BufferedInputStream(zip.getInputStream(entry)), digest);
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(output))) {
                    int n;
                    while ((n = input.read(buffer)) != -1) {
                        fileBytes += n;
                        if (fileBytes > MAX_FILE_UNCOMPRESSED) throw new IOException("ملف أكبر من الحد الآمن.");
                        out.write(buffer, 0, n);
                        copied += n;
                        int verifyProgress = 88 + (int) (copied * 10L / Math.max(1L, totalBytes));
                        progress(listener, Math.min(98, verifyProgress), "جارٍ التحقق من صفحات القرآن والأصوات…");
                    }
                }
                if (fileBytes != size) throw new IOException("حجم ملف غير مكتمل بعد فك الضغط.");
                String actual = hex(digest.digest());
                if (!actual.equals(sums.get(name))) throw new IOException("فشل تحقق SHA-256 لأحد الملفات: " + name);
            }
        }
        return copied;
    }

    private void validateCompleteSet(Map<String, String> checksums, Set<String> extracted) throws IOException {
        if (!extracted.equals(checksums.keySet())) throw new IOException("بعض الملفات في manifest غير موجودة في الحزم.");
    }

    private void replaceInstalledFiles() throws IOException {
        deleteTree(backupRoot);
        boolean hadOld = mediaRoot.exists();
        if (hadOld && !mediaRoot.renameTo(backupRoot)) throw new IOException("تعذر حفظ الموارد السابقة مؤقتًا.");
        if (!stagingRoot.renameTo(mediaRoot)) {
            if (hadOld) backupRoot.renameTo(mediaRoot);
            throw new IOException("تعذر تثبيت ملفات الموارد على الجهاز.");
        }
        deleteTree(backupRoot);
    }

    private boolean fileExists(String relative) {
        File file = new File(mediaRoot, relative);
        return file.isFile() && file.length() > 0;
    }

    private int percent(long completed, long total) {
        return Math.min(87, 2 + (int) (completed * 84L / Math.max(1L, total)));
    }

    private String formatMb(long bytes) { return String.format(Locale.US, "%.1f", bytes / 1048576.0); }

    private void progress(Listener listener, int percent, String message) {
        if (listener != null) listener.onProgress(Math.max(0, Math.min(99, percent)), message);
    }

    private byte[] readBounded(InputStream stream, long maxBytes) throws Exception {
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > maxBytes) throw new IOException("استجابة أكبر من الحد المسموح.");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) value.append(String.format(Locale.US, "%02x", b & 0xff));
        return value.toString();
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) message = error.getClass().getSimpleName();
        return message.length() > 180 ? message.substring(0, 180) : message;
    }

    private void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        if (!file.delete()) android.util.Log.w("RemoteAssetPack", "Could not delete temp file: " + file.getName());
    }
}
