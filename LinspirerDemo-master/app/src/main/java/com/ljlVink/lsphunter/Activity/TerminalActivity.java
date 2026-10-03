package com.ljlVink.lsphunter.Activity;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.huosoft.wisdomclass.linspirerdemo.R;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class TerminalActivity extends AppCompatActivity implements TerminalViewClient, TerminalSessionClient {

    private static final String START_DIR = "/storage/emulated/0";
    private static final int BASE_FONT_SIZE = 14;
    private static final String[] SU_PATHS = {
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/system/sbin/su", "/su/bin/su", "/debug_ramdisk/su"
    };

    private TerminalView terminalView;
    private TerminalSession terminalSession;
    private float fontSize = BASE_FONT_SIZE;
    private boolean ctrlHeld = false;
    private String suPath;
    private boolean sessionRoot = false;
    private long sessionStartTime = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terminal);
        terminalView = findViewById(R.id.term_view);
        terminalView.setTerminalViewClient(this);
        terminalView.setTextSize(BASE_FONT_SIZE);
        findViewById(R.id.btn_key_esc).setOnClickListener(v -> writeKey("\u001B"));
        findViewById(R.id.btn_key_tab).setOnClickListener(v -> writeKey("\t"));
        findViewById(R.id.btn_key_ctrl).setOnClickListener(v -> {
            ctrlHeld = !ctrlHeld;
            ((Button) v).setTextColor(ctrlHeld ? 0xFF3FD53F : 0xFFCCCCCC);
        });
        findViewById(R.id.btn_key_up).setOnClickListener(v -> writeKey("\u001B[A"));
        findViewById(R.id.btn_key_down).setOnClickListener(v -> writeKey("\u001B[B"));
        findViewById(R.id.btn_key_clear).setOnClickListener(v -> writeKey("clear\n"));
        suPath = findSu();
        if (suPath != null) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("终端模拟器")
                    .setMessage("检测到su (" + suPath + ")\n选择会话类型:\nroot终端内 pm/am/cmd/dumpsys 等系统命令可直接使用")
                    .setPositiveButton("普通终端", (dialog, which) -> startSession(false))
                    .setNegativeButton("root终端", (dialog, which) -> startSession(true))
                    .setCancelable(false)
                    .show();
        } else {
            startSession(false);
        }
        terminalView.postDelayed(() -> {
            terminalView.requestFocus();
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT);
        }, 300);
    }

    private static String findSu() {
        for (String path : SU_PATHS) {
            if (new File(path).exists()) {
                return path;
            }
        }
        return null;
    }

    private void startSession(boolean root) {
        sessionRoot = root && suPath != null;
        sessionStartTime = System.currentTimeMillis();
        ctrlHeld = false;
        if (terminalSession != null && terminalSession.isRunning()) {
            terminalSession.finishIfRunning();
        }
        String cwd = new File(START_DIR).isDirectory() ? START_DIR : "/";
        List<String> env = new ArrayList<>();
        env.add("TERM=xterm-256color");
        env.add("PATH=/system/bin:/system/xbin:/sbin:/vendor/bin");
        env.add("HOME=" + getFilesDir().getAbsolutePath());
        env.add("TMPDIR=" + getCacheDir().getAbsolutePath());
        env.add("SHELL=/system/bin/sh");
        env.add("ANDROID_ROOT=/system");
        env.add("ANDROID_DATA=/data");
        env.add("LANG=zh_CN.UTF-8");
        String cmdPath;
        if (sessionRoot) {
            cmdPath = suPath;
        } else {
            cmdPath = "/system/bin/sh";
            // 交互式mksh会读取$ENV文件:普通app进程无法直连package/activity等系统服务的
            // binder shellCommand通道,把pm/am/cmd包装为su转发(无su时给出明确提示)
            env.add("ENV=" + writeProfile(suPath != null).getAbsolutePath());
        }
        terminalSession = new TerminalSession(cmdPath, cwd, new String[0],
                env.toArray(new String[0]), null, this);
        terminalView.attachSession(terminalSession);
        String banner = sessionRoot
                ? "echo 'Linspirer Hunter 终端(root会话已就绪)'"
                : (suPath != null
                        ? "echo 'Linspirer Hunter 终端(普通会话; pm/am/cmd已自动经su转发)'"
                        : "echo 'Linspirer Hunter 终端(普通会话; 未检测到root, pm/am/cmd不可用)'");
        terminalView.postDelayed(() -> {
            if (terminalSession != null && terminalSession.isRunning()) {
                writeInput(banner + "\n");
            }
        }, 600);
    }

    private File writeProfile(boolean hasSu) {
        File profile = new File(getFilesDir(), "lsp_term_profile.sh");
        try (FileOutputStream fos = new FileOutputStream(profile)) {
            StringBuilder sb = new StringBuilder();
            sb.append("[ -r /system/etc/mkshrc ] && . /system/etc/mkshrc\n");
            if (hasSu) {
                sb.append("pm(){ su -c \"pm $*\"; }\n");
                sb.append("am(){ su -c \"am $*\"; }\n");
                sb.append("cmd(){ su -c \"cmd $*\"; }\n");
            } else {
                sb.append("pm(){ echo 'lsp: pm需要shell/root权限,普通app进程无法直连系统服务'; }\n");
                sb.append("am(){ echo 'lsp: am需要shell/root权限,普通app进程无法直连系统服务'; }\n");
                sb.append("cmd(){ echo 'lsp: cmd需要shell/root权限,普通app进程无法直连系统服务'; }\n");
            }
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignore) {
        }
        return profile;
    }

    private void writeInput(String data) {
        if (terminalSession != null && terminalSession.isRunning()) {
            byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
            terminalSession.write(bytes, 0, bytes.length);
        }
    }

    private void writeKey(String data) {
        writeInput(data);
        terminalView.requestFocus();
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT);
    }

    @SuppressLint("ShowToast")
    private void toast(String text) {
        runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onBackPressed() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("终端模拟器")
                .setMessage("确定退出终端?当前shell会话将被结束")
                .setPositiveButton("退出", (dialog, which) -> finish())
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        if (terminalSession != null) {
            terminalSession.finishIfRunning();
        }
        super.onDestroy();
    }

    // ===== TerminalViewClient =====

    @Override
    public float onScale(float scale) {
        fontSize = Math.max(8, Math.min(32, fontSize * scale));
        terminalView.setTextSize((int) fontSize);
        return fontSize;
    }

    @Override
    public void onSingleTapUp(MotionEvent e) {
        terminalView.requestFocus();
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT);
    }

    @Override
    public boolean shouldBackButtonBeMappedToEscape() {
        return false;
    }

    @Override
    public boolean shouldEnforceCharBasedInput() {
        return true;
    }

    @Override
    public boolean shouldUseCtrlSpaceWorkaround() {
        return false;
    }

    @Override
    public boolean isTerminalViewSelected() {
        return true;
    }

    @Override
    public void copyModeChanged(boolean copyMode) {
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) {
        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        if (ctrlHeld) {
            ctrlHeld = false;
            Button ctrl = findViewById(R.id.btn_key_ctrl);
            if (ctrl != null) {
                ctrl.setTextColor(0xFFCCCCCC);
            }
        }
        return false;
    }

    @Override
    public boolean onLongPress(MotionEvent e) {
        return false;
    }

    @Override
    public boolean readControlKey() {
        return ctrlHeld;
    }

    @Override
    public boolean readAltKey() {
        return false;
    }

    @Override
    public boolean readShiftKey() {
        return false;
    }

    @Override
    public boolean readFnKey() {
        return false;
    }

    @Override
    public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
        return false;
    }

    @Override
    public void onEmulatorSet() {
    }

    @Override
    public void logError(String tag, String message) {
    }

    @Override
    public void logWarn(String tag, String message) {
    }

    @Override
    public void logInfo(String tag, String message) {
    }

    @Override
    public void logDebug(String tag, String message) {
    }

    @Override
    public void logVerbose(String tag, String message) {
    }

    @Override
    public void logStackTraceWithMessage(String tag, String message, Exception e) {
    }

    @Override
    public void logStackTrace(String tag, Exception e) {
    }

    // ===== TerminalSessionClient =====

    @Override
    public void onTextChanged(TerminalSession changedSession) {
        if (terminalView.mEmulator != null && changedSession == terminalSession) {
            terminalView.onScreenUpdated();
        }
    }

    @Override
    public void onTitleChanged(TerminalSession changedSession) {
    }

    @Override
    public void onSessionFinished(TerminalSession finishedSession) {
        runOnUiThread(() -> {
            if (sessionRoot && System.currentTimeMillis() - sessionStartTime < 3000) {
                toast("root会话启动失败(未授权?),已回退普通终端");
                terminalView.postDelayed(() -> startSession(false), 300);
            } else {
                toast("shell会话已结束,正在重启新会话");
                terminalView.postDelayed(() -> startSession(sessionRoot), 500);
            }
        });
    }

    @Override
    public void onCopyTextToClipboard(TerminalSession session, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("terminal", text));
    }

    @Override
    public void onPasteTextFromClipboard(TerminalSession session) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (!cm.hasPrimaryClip()) {
            return;
        }
        CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
        if (text != null) {
            writeInput(text.toString());
        }
    }

    @Override
    public void onBell(TerminalSession session) {
    }

    @Override
    public void onColorsChanged(TerminalSession session) {
    }

    @Override
    public void onTerminalCursorStateChange(boolean state) {
    }

    @Override
    public Integer getTerminalCursorStyle() {
        return 0;
    }
}
