package io.github.babydragonmc.ais2a;

import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.*;
import android.view.View;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private TextView state;
    private EditText proxy;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Runnable refresh = new Runnable() {
        @Override public void run() { if(state != null) state.setText(BackendService.status); handler.postDelayed(this, 1000); }
    };
    private int dp(int size) { return Math.round(size * getResources().getDisplayMetrics().density); }
    private void text(LinearLayout root, String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setPadding(0,dp(8),0,dp(8)); root.addView(view);
    }
    private void button(LinearLayout root, String name, View.OnClickListener action) {
        Button button = new Button(this); button.setText(name); button.setAllCaps(false); button.setOnClickListener(action); root.addView(button);
    }
    private void error(Exception e) { new AlertDialog.Builder(this).setTitle("操作失败").setMessage(e.getMessage()).setPositiveButton("确定", null).show(); }
    private void copySecret(String name, String label) {
        try {
            ClipData clip = ClipData.newPlainText(label, BackendService.setting(this,name));
            if(Build.VERSION.SDK_INT >= 33) { android.os.PersistableBundle extras = new android.os.PersistableBundle(); extras.putBoolean("android.content.extra.IS_SENSITIVE",true); clip.getDescription().setExtras(extras); }
            getSystemService(android.content.ClipboardManager.class).setPrimaryClip(clip);
            Toast.makeText(this,label+"已复制",Toast.LENGTH_SHORT).show();
        } catch(IOException e) { error(e); }
    }
    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(24),dp(32),dp(24),dp(24));
        scroll.addView(root); setContentView(scroll);
        text(root, "AIS2A Android", 28);
        text(root, "手机独立反代 · 0.1.0 实验版", 15);
        state = new TextView(this); state.setTextSize(16); state.setPadding(0,dp(16),0,dp(16)); root.addView(state);
        text(root, "先启动核心，再进入管理页添加账户并启动生成服务。实验版使用“粘贴 Cookie”导入；桌面 Chrome 导入和 Camoufox 登录尚未适配。", 14);
        button(root, "启动本机核心", v -> {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
            startForegroundService(new Intent(this, BackendService.class));
        });
        button(root, "打开管理页面", v -> startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("http://127.0.0.1:18765/"))));
        button(root, "复制管理密码（用户名 admin）", v -> copySecret("ADMIN_PASSWORD", "管理密码"));
        button(root, "停止本机核心", v -> { stopService(new Intent(this, BackendService.class)); BackendService.status = "已停止"; });
        text(root, "出站代理（手机上的代理端口）", 17);
        proxy = new EditText(this); proxy.setSingleLine(true); proxy.setHint("http://127.0.0.1:7890"); proxy.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI); root.addView(proxy);
        try { proxy.setText(BackendService.setting(this, "PROXY")); } catch(IOException e) { error(e); }
        text(root, "需要可访问 Google 的网络。当前 Linux 核心的 Android DNS 兼容性待验证，推荐显式填写手机上已有的 HTTP / SOCKS5 代理。保存后停止并重新启动核心。", 13);
        button(root, "保存代理设置", v -> { try { BackendService.proxy(this, proxy.getText().toString()); Toast.makeText(this,"已保存，重启核心后生效",Toast.LENGTH_LONG).show(); } catch(IOException e) { error(e); } });
        text(root, "OpenAI 兼容接口：http://127.0.0.1:18765/v1", 14);
        button(root, "复制本机 API 密钥", v -> copySecret("PROXY_API_KEY", "API 密钥"));
        button(root, "检查本机管理端", v -> executor.execute(() -> {
            String message;
            try {
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection)new java.net.URL("http://127.0.0.1:18765/health").openConnection();
                conn.setConnectTimeout(3000); conn.setReadTimeout(3000);
                try {
                    int code = conn.getResponseCode();
                    byte[] bytes = new byte[256]; int length = conn.getInputStream().read(bytes);
                    String body = length > 0 ? new String(bytes,0,length,StandardCharsets.UTF_8) : "";
                    message = code == 200 && body.replaceAll("\\s", "").contains("\"status\":\"ok\"")
                            ? "AIStudio2API 管理端已验证 · HTTP 200" : "该端口未返回 AIStudio2API 健康响应";
                } finally { conn.disconnect(); }
            } catch(Exception e) { message = "管理端尚不可用：" + e.getMessage(); }
            final String result = message; handler.post(() -> { if(!isFinishing()) new AlertDialog.Builder(this).setTitle("检查结果").setMessage(result).setPositiveButton("确定",null).show(); });
        }));
        button(root, "查看最近的核心日志", v -> {
            StringBuilder log = new StringBuilder();
            File directory = new File(BackendService.directory(this),"runtime/logs");
            File[] files = directory.listFiles((dir,name) -> name.endsWith(".log"));
            if(files != null && files.length > 0) {
                java.util.Arrays.sort(files, (a,b) -> Long.compare(b.lastModified(),a.lastModified()));
                try { java.util.List<String> lines = java.nio.file.Files.readAllLines(files[0].toPath(), StandardCharsets.UTF_8);
                    for(String line:lines.subList(Math.max(0,lines.size()-30),lines.size())) log.append(line).append('\n');
                } catch(IOException e) { log.append(e.getMessage()); }
            }
            if(log.length()==0) log.append("尚无日志。请先启动核心。");
            TextView view = new TextView(this); view.setText(log.toString()); view.setPadding(dp(12),dp(12),dp(12),dp(12));
            ScrollView container = new ScrollView(this); container.addView(view);
            new AlertDialog.Builder(this).setTitle("仅本机显示，请勿公开账号日志").setView(container).setPositiveButton("关闭",null).show();
        });
        text(root, "社区实验移植版，基于 Mag1cFall/AIStudio2API v0.3.0（MIT）。账号与配置仅保存在本机私有目录；卸载会删除数据。", 12);
    }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); executor.shutdownNow(); super.onDestroy(); }
}
