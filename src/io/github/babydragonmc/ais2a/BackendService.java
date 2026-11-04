package io.github.babydragonmc.ais2a;

import android.app.*;
import android.content.*;
import android.os.*;
import java.lang.Process;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** Owns the packaged, checksum-pinned upstream core; never downloads executable code. */
public final class BackendService extends Service {
    private static final String CHANNEL = "backend";
    private Process process;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    static volatile String status = "未启动";
    static File directory(Context c) { return new File(c.getFilesDir(), "backend"); }
    static File config(Context c) { return new File(directory(c), ".env"); }

    static synchronized void prepare(Context c) throws IOException {
        File dir = directory(c);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建私有数据目录");
        File cfg = config(c);
        if (cfg.exists()) {
            String old = new String(Files.readAllBytes(cfg.toPath()), StandardCharsets.UTF_8);
            String migrated = old.replace("LISTEN_ADDR=127.0.0.1:8765\n", "LISTEN_ADDR=127.0.0.1:18765\n")
                    .replace("PREVIEW_LISTEN_ADDR=127.0.0.1:8766\n", "PREVIEW_LISTEN_ADDR=127.0.0.1:18766\n");
            if (!old.equals(migrated)) Files.write(cfg.toPath(), migrated.getBytes(StandardCharsets.UTF_8));
        }
        if (!cfg.exists()) {
            byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
            String key = "sk-local-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            String value = "LISTEN_ADDR=127.0.0.1:18765\nPREVIEW_LISTEN_ADDR=127.0.0.1:18766\n"
                    + "PROXY_API_KEY=" + key + "\nWAA_BACKEND=spidermonkey\nUPSTREAM_CHANNELS=playground\n"
                    + "AUTO_START=false\nREQUEST_BODY_LOG=false\nLOG_FILE=true\nFIRST_EVENT_HEDGE=off\nPROXY=\n";
            Files.write(cfg.toPath(), value.getBytes(StandardCharsets.UTF_8));
        }
        String current = new String(Files.readAllBytes(cfg.toPath()), StandardCharsets.UTF_8);
        String secure = current;
        if (!secure.endsWith("\n")) secure += "\n";
        if (secure.contains("ADMIN_AUTH_ENABLED=")) secure = secure.replaceAll("(?m)^ADMIN_AUTH_ENABLED=.*$", "ADMIN_AUTH_ENABLED=true");
        else secure += "ADMIN_AUTH_ENABLED=true\n";
        if (!java.util.regex.Pattern.compile("(?m)^ADMIN_PASSWORD=.+$").matcher(secure).find()) {
            byte[] password = new byte[24]; new SecureRandom().nextBytes(password);
            secure = secure.replaceAll("(?m)^ADMIN_PASSWORD=.*\\n?", "");
            secure += "ADMIN_PASSWORD=" + Base64.getUrlEncoder().withoutPadding().encodeToString(password) + "\n";
        }
        if (!current.equals(secure)) Files.write(cfg.toPath(),secure.getBytes(StandardCharsets.UTF_8));
    }

    static String setting(Context c, String name) throws IOException {
        prepare(c);
        for (String line : Files.readAllLines(config(c).toPath(), StandardCharsets.UTF_8))
            if (line.startsWith(name + "=")) return line.substring(name.length() + 1);
        return "";
    }

    static synchronized void proxy(Context c, String value) throws IOException {
        prepare(c);
        value = value.trim();
        if (!value.isEmpty()) {
            java.net.URI uri;
            try { uri = java.net.URI.create(value); } catch (IllegalArgumentException e) { throw new IOException("代理地址格式错误"); }
            if (!Arrays.asList("http", "https", "socks5").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getPort() < 1 || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || (uri.getPath() != null && !uri.getPath().isEmpty()))
                throw new IOException("请输入无账号密码的代理地址，如 http://127.0.0.1:7890");
        }
        List<String> lines = Files.readAllLines(config(c).toPath(), StandardCharsets.UTF_8);
        boolean found = false;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).startsWith("PROXY=")) { lines.set(i, "PROXY=" + value); found = true; }
        if (!found) lines.add("PROXY=" + value);
        File temp = new File(directory(c), ".env.tmp");
        Files.write(temp.toPath(), lines, StandardCharsets.UTF_8);
        Files.move(temp.toPath(), config(c).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "本机反代服务", NotificationManager.IMPORTANCE_LOW));
    }
    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, BackendService.class).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("AIS2A 本机反代").setContentText(text).setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "停止", stop).build()).build();
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        startForeground(1, notification("正在启动 127.0.0.1:18765"));
        synchronized (this) {
            if (process != null) return START_NOT_STICKY;
            try {
                prepare(this);
                File binary = new File(getApplicationInfo().nativeLibraryDir, "libais2acore.so");
                if (!binary.isFile()) throw new IOException("安装包缺少当前架构的反代核心");
                ProcessBuilder builder = new ProcessBuilder(binary.getAbsolutePath(), "-open-ui=false");
                builder.directory(directory(this));
                builder.environment().put("HOME", directory(this).getAbsolutePath());
                builder.environment().put("TMPDIR", getCacheDir().getAbsolutePath());
                builder.environment().put("GOMEMLIMIT", "384MiB");
                builder.redirectErrorStream(true);
                // The core writes its own rotated logs. No credentials are copied to Android logcat.
                builder.redirectOutput(new File(directory(this), "bootstrap.log"));
                process = builder.start();
                status = "核心进程已启动，正在检查管理端";
                final Process launched = process;
                executor.execute(() -> monitor(launched));
            } catch (Exception e) { status = "启动失败：" + e.getMessage(); stopSelf(); }
        }
        return START_NOT_STICKY;
    }
    private void monitor(Process child) {
        try {
            boolean healthy = false;
            for (int i = 0; i < 60 && child.isAlive(); i++) {
                try {
                    java.net.HttpURLConnection connection = (java.net.HttpURLConnection)new java.net.URL("http://127.0.0.1:18765/health").openConnection();
                    connection.setConnectTimeout(500); connection.setReadTimeout(500);
                    try {
                        if (connection.getResponseCode() == 200) {
                            byte[] bytes = new byte[256]; int length = connection.getInputStream().read(bytes);
                            String body = length > 0 ? new String(bytes, 0, length, StandardCharsets.UTF_8) : "";
                            healthy = body.replaceAll("\\s", "").contains("\"status\":\"ok\"");
                        }
                    } finally { connection.disconnect(); }
                    if (healthy) break;
                } catch (IOException ignored) { }
                Thread.sleep(500);
            }
            status = healthy ? "管理端已就绪 · 127.0.0.1:18765" : "核心尚未就绪，请查看运行日志";
            getSystemService(NotificationManager.class).notify(1, notification(status));
            int exit = child.waitFor();
            status = "核心已退出（" + exit + "）";
            stopSelf();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    @Override public void onDestroy() {
        synchronized(this) { if (process != null) { process.destroy(); process = null; } }
        executor.shutdownNow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
