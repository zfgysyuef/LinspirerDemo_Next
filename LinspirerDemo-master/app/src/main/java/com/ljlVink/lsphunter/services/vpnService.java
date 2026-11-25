package com.ljlVink.lsphunter.services;

import static androidx.core.app.NotificationCompat.PRIORITY_HIGH;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.ProxyInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.huosoft.wisdomclass.linspirerdemo.R;
import com.ljlVink.lsphunter.utils.DataUtils;

import java.io.IOException;

import activitylauncher.MainActivity;

public class vpnService extends VpnService {
    private static final String TAG = "LinspirerVpn";
    private static final String EXTRA_COMMAND = "Command";

    public static final String MODE_BLOCK = "block";
    public static final String MODE_CUSTOM_MDM = "custom_mdm";
    public static final String KEY_MODE = "vpn_behavior";
    public static final String KEY_MDM_HOST = "mdm_server_host";
    public static final String KEY_MDM_PORT = "mdm_server_port";
    public static final String DEFAULT_MDM_HOST = "10.0.2.2";
    public static final int DEFAULT_MDM_PORT = 883;

    private enum Command {start, reload, stop}

    private ParcelFileDescriptor vpn;
    private MdmProxyServer proxyServer;

    @Override
    public synchronized int onStartCommand(Intent intent, int flags, int startId) {
        Command command = readCommand(intent);
        if (command != Command.stop && DataUtils.readint(this, "vpnmode") != 1) {
            stopVpn();
            stopSelf();
            return START_NOT_STICKY;
        }
        switch (command) {
            case start:
                if (vpn == null) {
                    vpn = vpnStart();
                    if (vpn == null) {
                        stopForeground(true);
                        stopSelf();
                        return START_NOT_STICKY;
                    }
                }
                break;
            case reload:
                ParcelFileDescriptor previous = vpn;
                ParcelFileDescriptor replacement = vpnStart();
                if (replacement != null) {
                    vpn = replacement;
                    closeDescriptor(previous);
                }
                break;
            case stop:
                stopVpn();
                stopSelf();
                return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private Command readCommand(Intent intent) {
        if (intent == null) {
            return Command.start;
        }
        Object value = intent.getSerializableExtra(EXTRA_COMMAND);
        return value instanceof Command ? (Command) value : Command.start;
    }

    private String createNotificationChannel(String channelId, String channelName) {
        NotificationChannel channel = new NotificationChannel(
                channelId, channelName, NotificationManager.IMPORTANCE_LOW);
        channel.setLightColor(Color.WHITE);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(channel);
        return channelId;
    }

    private void showNotification(String detail) {
        String channelId = createNotificationChannel(getPackageName(), "lsphunter_vpn_channel");
        PendingIntent configure = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new NotificationCompat.Builder(this, channelId)
                .setOngoing(true)
                .setPriority(PRIORITY_HIGH)
                .setSmallIcon(R.drawable.transpatent_page)
                .setContentTitle("Linspirer Hunter VPN")
                .setContentText(detail)
                .setContentIntent(configure)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
        startForeground(123, notification);
    }

    private synchronized ParcelFileDescriptor vpnStart() {
        String mode = DataUtils.readStringValue(this, KEY_MODE, MODE_BLOCK);
        Builder builder = new Builder()
                .setSession(MODE_CUSTOM_MDM.equals(mode)
                        ? "Linspirer Custom MDM"
                        : "Linspirer Network Block")
                .addAddress("10.1.10.1", 32)
                .addAddress("fd00:1:fd00:1:fd00:1:fd00:1", 128)
                .setConfigureIntent(PendingIntent.getActivity(
                        this,
                        0,
                        new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));

        try {
            builder.addAllowedApplication("com.android.launcher3");
            if (DataUtils.readint(this, "disallow_appstore_internet", 1) == 1) {
                builder.addAllowedApplication("com.ndwill.swd.appstore");
            }
        } catch (Exception error) {
            Log.w(TAG, "Unable to configure per-app VPN", error);
        }

        MdmProxyServer replacementProxy = null;
        try {
            if (MODE_CUSTOM_MDM.equals(mode)) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    throw new IllegalStateException("自定义 MDM 代理模式需要 Android 10 或更高版本");
                }
                String host = DataUtils.readStringValue(this, KEY_MDM_HOST, DEFAULT_MDM_HOST);
                int port = DataUtils.readint(this, KEY_MDM_PORT, DEFAULT_MDM_PORT);
                replacementProxy = new MdmProxyServer(this, host, port);
                int localPort = replacementProxy.start();
                builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", localPort));
                // Proxy-aware traffic uses localhost. Other traffic falls through because this
                // mode deliberately installs no default route into the TUN interface.
                builder.allowFamily(OsConstants.AF_INET);
                builder.allowFamily(OsConstants.AF_INET6);
                builder.setMetered(false);
                showNotification("自定义 MDM → " + replacementProxy.getPrivateEndpoint());
            } else {
                builder.addRoute("0.0.0.0", 0);
                builder.addRoute("0:0:0:0:0:0:0:0", 0);
                showNotification("领创联网已阻断");
            }

            ParcelFileDescriptor descriptor = builder.establish();
            if (descriptor == null) {
                closeProxy(replacementProxy);
                return null;
            }
            MdmProxyServer previousProxy = proxyServer;
            proxyServer = replacementProxy;
            closeProxy(previousProxy);
            return descriptor;
        } catch (Throwable error) {
            Log.e(TAG, "Unable to establish VPN", error);
            closeProxy(replacementProxy);
            return null;
        }
    }

    private synchronized void stopVpn() {
        closeDescriptor(vpn);
        vpn = null;
        closeProxy(proxyServer);
        proxyServer = null;
        stopForeground(true);
    }

    private static void closeDescriptor(ParcelFileDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        try {
            descriptor.close();
        } catch (IOException ignored) {
        }
    }

    private static void closeProxy(MdmProxyServer proxy) {
        if (proxy != null) {
            proxy.close();
        }
    }

    private final BroadcastReceiver connectivityChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.hasExtra(ConnectivityManager.EXTRA_NETWORK_TYPE)
                    && intent.getIntExtra(
                    ConnectivityManager.EXTRA_NETWORK_TYPE,
                    ConnectivityManager.TYPE_DUMMY) == ConnectivityManager.TYPE_WIFI) {
                reload(null, vpnService.this);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(connectivityChangedReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(connectivityChangedReceiver, filter);
        }
    }

    @Override
    public void onDestroy() {
        stopVpn();
        try {
            unregisterReceiver(connectivityChangedReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        stopVpn();
        DataUtils.saveintvalue(this, "vpnmode", 0);
        super.onRevoke();
    }

    private static void sendCommand(Context context, Command command) {
        Intent intent = new Intent(context, vpnService.class);
        intent.putExtra(EXTRA_COMMAND, command);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void start(Context context) {
        sendCommand(context, Command.start);
    }

    public static void reload(String network, Context context) {
        sendCommand(context, Command.reload);
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, vpnService.class);
        intent.putExtra(EXTRA_COMMAND, Command.stop);
        context.startService(intent);
    }
}
