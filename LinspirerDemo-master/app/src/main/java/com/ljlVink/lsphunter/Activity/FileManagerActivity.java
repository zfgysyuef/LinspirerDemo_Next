package com.ljlVink.lsphunter.Activity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.huosoft.wisdomclass.linspirerdemo.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class FileManagerActivity extends AppCompatActivity {

    private static final String HOME_DIR = "/storage/emulated/0";

    private File curDir;
    private File homeDir;
    private File clipFile = null;
    private boolean clipCut = false;
    private List<File> entries = new ArrayList<>();
    private TextView tvPath;
    private TextView tvClip;
    private View clipBar;
    private FileAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_filemanager);
        tvPath = findViewById(R.id.tv_fm_path);
        tvClip = findViewById(R.id.tv_fm_clip);
        clipBar = findViewById(R.id.bar_fm_clip);
        ListView listView = findViewById(R.id.lv_fm_files);
        adapter = new FileAdapter();
        listView.setAdapter(adapter);
        homeDir = new File(HOME_DIR).isDirectory() ? new File(HOME_DIR) : new File("/");
        findViewById(R.id.btn_fm_up).setOnClickListener(v -> goUp());
        findViewById(R.id.btn_fm_home).setOnClickListener(v -> navigate(homeDir));
        findViewById(R.id.btn_fm_paste).setOnClickListener(v -> paste());
        findViewById(R.id.btn_fm_clip_clear).setOnClickListener(v -> clearClipboard());
        listView.setOnItemClickListener((parent, view, position, id) -> {
            File f = entries.get(position);
            if (f.isDirectory() && f.canRead()) {
                navigate(f);
            } else {
                showItemMenu(f, true);
            }
        });
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            showItemMenu(entries.get(position), false);
            return true;
        });
        navigate(homeDir);
    }

    private void navigate(File dir) {
        curDir = dir;
        File[] files = dir.listFiles();
        entries.clear();
        if (files != null) {
            Arrays.sort(files, (a, b) -> {
                if (a.isDirectory() != b.isDirectory()) {
                    return a.isDirectory() ? -1 : 1;
                }
                return a.getName().toLowerCase(Locale.ROOT)
                        .compareTo(b.getName().toLowerCase(Locale.ROOT));
            });
            entries.addAll(Arrays.asList(files));
        }
        if (dir.equals(homeDir) && files == null) {
            toast("无法读取目录(权限被拒绝?)");
        }
        tvPath.setText(dir.getAbsolutePath());
        adapter.notifyDataSetChanged();
    }

    private void goUp() {
        File parent = curDir.getParentFile();
        if (parent != null && parent.canRead()) {
            navigate(parent);
        } else {
            toast("已经是顶层目录");
        }
    }

    private void showItemMenu(File f, boolean includeOpen) {
        List<String> actions = new ArrayList<>();
        if (includeOpen) {
            actions.add(f.isDirectory() ? "进入" : "打开");
        }
        actions.add("复制");
        actions.add("剪切(移动)");
        new MaterialAlertDialogBuilder(this)
                .setIcon(f.isDirectory() ? R.drawable.ic_folder_item : R.drawable.ic_file_item)
                .setTitle(f.getName())
                .setItems(actions.toArray(new String[0]), (dialog, which) -> {
                    String action = actions.get(which);
                    if (action.equals("进入")) {
                        navigate(f);
                    } else if (action.equals("打开")) {
                        openFile(f);
                    } else if (action.equals("复制")) {
                        setClipboard(f, false);
                    } else {
                        setClipboard(f, true);
                    }
                })
                .show();
    }

    private void setClipboard(File f, boolean cut) {
        clipFile = f;
        clipCut = cut;
        tvClip.setText((cut ? "待移动: " : "待复制: ") + f.getName());
        clipBar.setVisibility(View.VISIBLE);
    }

    private void clearClipboard() {
        clipFile = null;
        clipBar.setVisibility(View.GONE);
    }

    private void paste() {
        if (clipFile == null || !clipFile.exists()) {
            clearClipboard();
            toast("源文件已不存在");
            return;
        }
        if (clipFile.getParentFile() != null && clipFile.getParentFile().equals(curDir)) {
            toast("已在源目录中");
            return;
        }
        final File src = clipFile;
        final boolean cut = clipCut;
        toast(cut ? "正在移动..." : "正在复制...");
        new Thread(() -> {
            File dst = uniqueName(curDir, src.getName());
            String error = null;
            if (cut) {
                if (!src.renameTo(dst)) {
                    error = copyRecursive(src, dst);
                    if (error == null) {
                        deleteRecursive(src);
                    }
                }
            } else {
                error = copyRecursive(src, dst);
            }
            final String err = error;
            final boolean done = err == null;
            runOnUiThread(() -> {
                if (done) {
                    toast((cut ? "移动完成: " : "复制完成: ") + dst.getName());
                    if (cut) {
                        clearClipboard();
                    }
                } else {
                    toast("操作失败:" + err);
                }
                navigate(curDir);
            });
        }).start();
    }

    private File uniqueName(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) {
            return f;
        }
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        int n = 1;
        while (f.exists()) {
            f = new File(dir, base + "(" + n + ")" + ext);
            n++;
        }
        return f;
    }

    private String copyRecursive(File src, File dst) {
        try {
            if (src.isDirectory()) {
                if (!dst.exists() && !dst.mkdirs()) {
                    return "无法创建目录 " + dst;
                }
                File[] children = src.listFiles();
                if (children != null) {
                    for (File child : children) {
                        String err = copyRecursive(child, new File(dst, child.getName()));
                        if (err != null) {
                            return err;
                        }
                    }
                }
                return null;
            }
            copyFile(src, dst);
            return null;
        } catch (IOException e) {
            return e.toString();
        }
    }

    private void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1024 * 1024];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
        }
    }

    private void deleteRecursive(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursive(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private void openFile(File f) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileProvider", f);
            String ext = MimeTypeMap.getFileExtensionFromUrl(f.getName());
            String mime = ext == null ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(Locale.ROOT));
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mime == null ? "*/*" : mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            toast("无法打开:" + e);
        }
    }

    private String formatSize(long size) {
        if (size < 1024) {
            return size + " B";
        }
        if (size < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", size / 1024.0);
        }
        if (size < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", size / 1024.0 / 1024.0);
        }
        return String.format(Locale.ROOT, "%.2f GB", size / 1024.0 / 1024.0 / 1024.0);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        if (!curDir.equals(homeDir) && curDir.getParentFile() != null && curDir.getParentFile().canRead()) {
            goUp();
        } else {
            super.onBackPressed();
        }
    }

    class FileAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Object getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return 0;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = View.inflate(FileManagerActivity.this, R.layout.item_file, null);
            }
            File f = entries.get(position);
            ImageView icon = convertView.findViewById(R.id.iv_file_icon);
            TextView name = convertView.findViewById(R.id.tv_file_name);
            TextView info = convertView.findViewById(R.id.tv_file_info);
            icon.setImageResource(f.isDirectory() ? R.drawable.ic_folder_item : R.drawable.ic_file_item);
            name.setText(f.getName());
            if (f.isDirectory()) {
                info.setText("目录");
            } else {
                info.setText(f.canRead() ? formatSize(f.length()) : "无法读取");
            }
            return convertView;
        }
    }
}
