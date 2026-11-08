package ru.narezany.avtomaster;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Автомастерская — приложение вокруг игры (assets/index.html).
 *
 * Что делает обёртка, чтобы это ощущалось приложением, а не сайтом:
 *  - полноэкранный режим без адресной строки и системных панелей;
 *  - своя заставка вместо белого мигания при запуске;
 *  - кнопка «Назад»: из гаража/салона возвращает на смену,
 *    второй нажатый «Назад» на смене закрывает игру;
 *  - экран не гаснет, пока игра открыта;
 *  - отключены зум, выделение текста и системный масштаб шрифта,
 *    чтобы вёрстка не разъезжалась;
 *  - игра полностью офлайн, любые внешние переходы блокируются.
 */
public class MainActivity extends Activity {

    private static final String TAG = "AvtoMaster";
    private static final String GAME_URL = "file:///android_asset/index.html";
    private static final int COLOR_BG = 0xFF0D1014;
    private static final long EXIT_WINDOW_MS = 2000L;

    private WebView web;
    private View splash;
    private TextView splashSub;

    private boolean pageLoaded = false;
    private boolean loadFailed = false;

    /** Текущая вкладка игры, её сообщает сам JS через AndroidApp.setTab(). */
    private volatile String currentTab = "shift";

    private long lastBackAt = 0L;
    private Toast backToast;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Игра не должна прерываться, пока телефон сам не погасит экран.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyImmersive();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BG);

        web = new WebView(this);
        configureWebView(web);
        web.addJavascriptInterface(new Bridge(), "AndroidApp");
        web.setWebViewClient(new GameClient());
        web.setWebChromeClient(new GameChrome());
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        splash = buildSplash();
        root.addView(splash, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);

        // Состояние игры живёт в localStorage, поэтому при перезапуске
        // достаточно просто открыть страницу заново.
        web.loadUrl(GAME_URL);
    }

    /* ================= WebView ================= */

    private void configureWebView(WebView view) {
        WebSettings s = view.getSettings();

        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDefaultTextEncodingName("utf-8");

        // Нужен доступ к файлам, чтобы открыть игру из assets.
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);

        // Игра свёрстана под телефон: зум только мешает.
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);

        // Звук включается по тапу, но без требования жеста у самого WebView.
        s.setMediaPlaybackRequiresUserGesture(false);

        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setSaveFormData(false);
        s.setGeolocationEnabled(false);

        // Тёмная тема игры не должна «автоперекрашиваться» системой.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            s.setForceDark(WebSettings.FORCE_DARK_OFF);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            s.setAlgorithmicDarkeningAllowed(false);
        }

        view.setBackgroundColor(COLOR_BG);
        view.setOverScrollMode(View.OVER_SCROLL_NEVER);
        view.setVerticalScrollBarEnabled(false);
        view.setHorizontalScrollBarEnabled(false);
        view.setLongClickable(false);
        view.setHapticFeedbackEnabled(false);

        boolean debuggable =
                (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        WebView.setWebContentsDebuggingEnabled(debuggable);
    }

    private class GameClient extends WebViewClient {

        /** Игра офлайн и одностраничная: наружу не уходим. */
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return true;
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            revealGame();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            showLoadError();
        }
    }

    private class GameChrome extends WebChromeClient {
        @Override
        public boolean onConsoleMessage(ConsoleMessage message) {
            Log.d(TAG, message.message() + " @" + message.lineNumber());
            return true;
        }
    }

    /* ================= заставка ================= */

    private View buildSplash() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(COLOR_BG);
        box.setClickable(true);

        TextView icon = new TextView(this);
        icon.setText("\uD83E\uDDF0"); // 🧰
        icon.setTextSize(54);
        icon.setGravity(Gravity.CENTER);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(22);
        title.setTextColor(0xFFE8EEF6);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(14), 0, dp(6));

        splashSub = new TextView(this);
        splashSub.setText(R.string.splash_loading);
        splashSub.setTextSize(13);
        splashSub.setTextColor(0xFF8B97A8);
        splashSub.setGravity(Gravity.CENTER);

        box.addView(icon);
        box.addView(title);
        box.addView(splashSub);

        box.setOnClickListener(v -> {
            if (loadFailed) {
                loadFailed = false;
                splashSub.setText(R.string.splash_loading);
                web.loadUrl(GAME_URL);
            }
        });
        return box;
    }

    private void revealGame() {
        if (pageLoaded || loadFailed) return;
        pageLoaded = true;
        // Небольшая пауза, чтобы не мелькала пустая тёмная страница.
        web.postDelayed(() -> {
            if (isFinishing() || splash == null) return;
            splash.animate()
                    .alpha(0f)
                    .setDuration(260)
                    .withEndAction(() -> splash.setVisibility(View.GONE))
                    .start();
        }, 300);
    }

    private void showLoadError() {
        if (pageLoaded) return;
        loadFailed = true;
        if (splashSub != null) splashSub.setText(R.string.splash_error);
    }

    /* ================= кнопка «Назад» ================= */

    @Override
    public void onBackPressed() {
        handleBack();
    }

    private void handleBack() {
        if (web != null && !"shift".equals(currentTab)) {
            web.evaluateJavascript(
                    "window.gameGoShift&&window.gameGoShift();", null);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackAt < EXIT_WINDOW_MS) {
            if (backToast != null) backToast.cancel();
            finish();
            return;
        }
        lastBackAt = now;
        if (backToast != null) backToast.cancel();
        backToast = Toast.makeText(this, R.string.exit_hint, Toast.LENGTH_SHORT);
        backToast.show();
    }

    /* ================= мостик в JS ================= */

    public class Bridge {

        /** Вызывается из игры при смене вкладки. */
        @JavascriptInterface
        public void setTab(String tab) {
            currentTab = (tab == null || tab.isEmpty()) ? "shift" : tab;
        }

        /** Вызывается, когда игра полностью отрисовалась. */
        @JavascriptInterface
        public void setReady() {
            runOnUiThread(MainActivity.this::revealGame);
        }

        /** Выход из игры (на будущее, из JS). */
        @JavascriptInterface
        public void exit() {
            runOnUiThread(MainActivity.this::finish);
        }
    }

    /* ================= системный интерфейс ================= */

    private void applyImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /* ================= жизненный цикл ================= */

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) {
            // ставим игровой таймер на паузу, пока приложение в фоне
            web.evaluateJavascript("window.gamePause&&window.gamePause();", null);
            web.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.onResume();
            web.evaluateJavascript("window.gameResume&&window.gameResume();", null);
        }
        applyImmersive();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.removeJavascriptInterface("AndroidApp");
            web.stopLoading();
            web.loadUrl("about:blank");
            web.setWebChromeClient(null);
            web.setWebViewClient(null);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
