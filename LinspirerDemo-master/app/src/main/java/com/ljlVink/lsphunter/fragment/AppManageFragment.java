package com.ljlVink.lsphunter.fragment;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Environment;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;
import com.huosoft.wisdomclass.linspirerdemo.R;
import com.ljlVink.lsphunter.bean.AppBean;
import com.ljlVink.lsphunter.utils.LoadApptask;
import com.ljlVink.core.hackmdm.v2.HackMdm;
import com.ljlVink.lsphunter.utils.DataUtils;
import com.ljlVink.lsphunter.utils.Sysutils;
import com.ljlVink.lsphunter.utils.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class AppManageFragment {
    View view;
    Context ctx;
    private List<AppBean> mItems;
    private AppAdapter mAdapter;
    private LoadApptask mLoadAppUsageTask;
    private ArrayList<String> lspdemopkgname;

    private static final String[] TAB_NAMES = {"已安装app", "系统app"};

    public AppManageFragment(View view, Context ctx){
        this.view=view;
        this.ctx=ctx;
    }
    public void HandleFragment(){
        lspdemopkgname = Sysutils.FindLspDemoPkgName(ctx,"linspirerdemo");
        int c = 0;
        TabLayout tabLayout = view.findViewById(R.id.tab_condition);
        for (String name : TAB_NAMES) {
            TabLayout.Tab tab = tabLayout.newTab();
            tab.setTag(c);
            tab.view.setOnClickListener(v -> onTabClick((int) tab.getTag()));
            tabLayout.addTab(tab.setText(name));
            c++;
        }
        onTabClick(0);

    }
    public void onTabClick(int position) {
        switch (position) {
            case 0:
                getapps("已经安装app",1);
                break;
            case 1:
                getapps("系统app",2);
                break;
        }
    }
    private void getapps(String str,int center) {
        mLoadAppUsageTask = new LoadApptask(center, list -> {
            mItems = list;
            initAdapter();
        });
        mLoadAppUsageTask.execute();
    }
    private void initAdapter() {
        mAdapter = new AppAdapter();
        ListView rc = view.findViewById(R.id.rv_app_usage);
        rc.setAdapter(mAdapter);
        rc.setOnItemClickListener((adapterView, view, i, l) -> {
            String appName = mItems.get(i).getAppName();
            String pkgname = mItems.get(i).getPackageName();
            ArrayList<String> actions = new ArrayList<>();
            actions.add("打开");
            actions.add("提取apk");
            actions.add("卸载");
            boolean canTransferOwner = lspdemopkgname.contains(pkgname);
            if (canTransferOwner) {
                actions.add("转移权限");
            } else if (!pkgname.equals(ctx.getPackageName())) {
                actions.add("带SN参数启动");
            }
            View titleView = View.inflate(ctx, R.layout.dialog_app_actions_title, null);
            ((ImageView) titleView.findViewById(R.id.iv_dialog_app_icon)).setImageDrawable(Sysutils.getAppIcon(ctx, pkgname));
            ((TextView) titleView.findViewById(R.id.tv_dialog_app_name)).setText(appName);
            ((TextView) titleView.findViewById(R.id.tv_dialog_pkg_name)).setText("包名:" + pkgname);
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(ctx)
                    .setCustomTitle(titleView)
                    .setItems(actions.toArray(new String[0]), (dialog1, which) -> {
                        if (which == 0) {
                            try {
                                ctx.startActivity(ctx.getPackageManager().getLaunchIntentForPackage(pkgname));
                            } catch (Exception e) {
                                Toast.ShowErr(ctx, "出现错误");
                            }
                        } else if (which == 1) {
                            extractApk(pkgname, appName);
                        } else if (which == 2) {
                            HackMdm.DeviceMDM.unblockApp(pkgname);
                            Intent intent = new Intent(Intent.ACTION_DELETE);
                            intent.setData(Uri.parse("package:" + pkgname));
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            ctx.startActivity(intent);
                        } else {
                            if (canTransferOwner) {
                                HackMdm.DeviceMDM.transferOwner(new ComponentName(pkgname, "com.huosoft.wisdomclass.linspirerdemo.AR"));
                            } else {
                                startWithSN(pkgname);
                            }
                        }
                    });
            builder.create().show();

        });
        rc.setOnItemLongClickListener((adapterView, view, i, l) -> {
            String appName=mItems.get(i).getAppName();
            String pkgname=mItems.get(i).getPackageName();

            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(ctx)
                    .setTitle(appName)
                    .setMessage("包名:" + pkgname + "\n")
                    .setPositiveButton("冻结", (dialog1, which) -> HackMdm.DeviceMDM.iceApp(pkgname, true)).setIcon(Sysutils.getAppIcon(ctx, pkgname));
            builder.setNegativeButton("解冻", (dialog, which) -> HackMdm.DeviceMDM.iceApp(pkgname, false));
            builder.create().show();
            return true;
        });

    }

    private void startWithSN(String pkgname) {
        try {
            HackMdm.DeviceMDM.killApplicationProcess(pkgname);
            ctx.startActivity(ctx.getPackageManager().getLaunchIntentForPackage(pkgname));
            Thread thread = new Thread(() -> {
                Looper.prepare();
                for (int ii = 1; ii <= 7; ii++) {
                    try {
                        Thread.sleep(500);
                    } catch (Exception ignore) {
                    }
                    String sn = DataUtils.readStringValue(ctx, "SN", HackMdm.DeviceMDM.getSerialCode());
                    if (sn.equals("null")) {
                        Toast.ShowErr(ctx, "未设置SN");
                        return;
                    }
                    ctx.sendBroadcast(new Intent("com.linspirer.edu.getdevicesn"));
                    Intent intent8 = new Intent("com.android.laucher3.mdm.obtaindevicesn");
                    intent8.putExtra("device_sn", sn);
                    ctx.sendBroadcast(intent8);
                }
                Looper.loop();
            });
            thread.start();
        } catch (Exception e) {
            Toast.ShowErr(ctx, "启动失败");
        }
    }

    private void extractApk(String pkgname, String appName) {
        Toast.ShowInfo(ctx, "正在提取:" + appName);
        new Thread(() -> {
            try {
                PackageManager pm = ctx.getPackageManager();
                ApplicationInfo appInfo = pm.getApplicationInfo(pkgname, 0);
                String version = "";
                try {
                    PackageInfo pkgInfo = pm.getPackageInfo(pkgname, 0);
                    if (pkgInfo.versionName != null) {
                        version = "_" + pkgInfo.versionName;
                    }
                } catch (Exception ignore) {
                }
                String baseName = sanitizeFileName(appName) + "_" + pkgname + version;
                String[] splits = appInfo.splitPublicSourceDirs;
                File out = extractTo(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LinspirerHunter"), appInfo, baseName, splits);
                if (out == null) {
                    out = extractTo(ctx.getExternalFilesDir("apk"), appInfo, baseName, splits);
                }
                if (out == null) {
                    Toast.ShowErr(ctx, "提取失败:无法写入储存");
                } else {
                    String extra = (splits != null && splits.length > 0) ? "(split应用,共提取" + (splits.length + 1) + "个文件)" : "";
                    Toast.ShowSuccess(ctx, "提取成功" + extra + ":" + out.getAbsolutePath());
                }
            } catch (PackageManager.NameNotFoundException e) {
                Toast.ShowErr(ctx, "提取失败:应用可能已卸载");
            } catch (Exception e) {
                Toast.ShowErr(ctx, "提取失败:" + e);
            }
        }).start();
    }

    private File extractTo(File dir, ApplicationInfo appInfo, String baseName, String[] splits) {
        if (dir == null) {
            return null;
        }
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            if (splits == null || splits.length == 0) {
                File outFile = new File(dir, baseName + ".apk");
                copyFile(new File(appInfo.sourceDir), outFile);
                return outFile;
            }
            File outDir = new File(dir, baseName + "_split");
            if (!outDir.exists() && !outDir.mkdirs()) {
                return null;
            }
            copyFile(new File(appInfo.sourceDir), new File(outDir, "base.apk"));
            for (String split : splits) {
                copyFile(new File(split), new File(outDir, new File(split).getName()));
            }
            return outDir;
        } catch (IOException e) {
            return null;
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

    private static String sanitizeFileName(String name) {
        if (name == null) {
            return "app";
        }
        return name.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
    }
        class AppAdapter extends BaseAdapter {
            @Override
            public int getCount() {
                return mItems.size();
            }
            @Override
            public Object getItem(int position) {
                return mItems.get(position);
            }

            @Override
            public long getItemId(int position) {
                return 0;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                if (convertView == null) {
                    convertView = View.inflate(ctx, R.layout.item_apps, null);
                }
                ImageView imageView = (ImageView) convertView.findViewById(R.id.id_iv_app_icon);
                TextView textView = (TextView) convertView.findViewById(R.id.id_tv_app_name);
                imageView.setImageDrawable(mItems.get(position).getAppIcon());
                textView.setText(mItems.get(position).getAppName());
                return convertView;
            }
        }


}
