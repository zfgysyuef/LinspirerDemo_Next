package com.ljlVink.core.hackmdm.v2;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.widget.Toast;

import com.huosoft.wisdomclass.linspirerdemo.AR;

import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * 领创 hem 6.10.005+ (bj101Launcher) 兼容实现,基于GenericMDM覆写差异项.
 *
 * 6.10相对旧版的变化(逆向EduAppReceiver/GuardSecureApplication/u4/u5/md得出):
 * 1. com.linspirer.edu.*第三方广播接收器完整保留,extra名称不变
 *    (appwhitelist/appnetwhitelist/path/device_name/packageName/packageNames)
 * 2. 新增入口闸门u4.E(Intent): launcher偏好release_control==1,或华为
 *    DeviceControlManager.getControlMode()==2(严格管控)时,所有edu广播被直接丢弃;
 *    非华为设备(impl未初始化)或getControlMode抛异常(返回1)时放行
 * 3. ExecuteCmdService本地后门已移除,command_*命令仅剩云端推送通道
 * 4. hide_show_apps/logoutworkspace/enablePhoneAndSms/disable.gps等action已删除,
 *    enablePhoneAndSms拆分为enable_phone/enable_sms
 *
 * 对策: 本app成为DeviceOwner后反射DeviceControlManager.setControlMode(admin,1)
 * 把严格管控改为宽松,广播闸门即恢复放行
 */
public class LinspirerHemMDM extends GenericMDM {
    public static LinspirerHemMDM hemMDM;

    public static LinspirerHemMDM getInstance(Context context) {
        if (hemMDM == null) {
            hemMDM = new LinspirerHemMDM();
            mContext = context;
            dPm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            admin = new ComponentName(context, AR.class);
        }
        return hemMDM;
    }

    @Override
    public String getMDMName() {
        return "Linspirer_HEM_6.10";
    }

    private void edu(String action) {
        try {
            Intent intent = new Intent(action);
            intent.setPackage("com.android.launcher3");
            mContext.sendBroadcast(intent);
        } catch (Throwable ignore) {
        }
    }

    @Override
    public void sendBackDoorLINS(String code, int active) {
        // ExecuteCmdService后门在6.10已被移除,startService为无效操作;
        // 按命令语义改走仍然存活的edu广播
        try {
            switch (code) {
                case "command_camera":
                    edu("com.linspirer.edu.no_control_camera");
                    edu("com.linspirer.edu.enable_camera");
                    break;
                case "command_otg":
                    edu("com.linspirer.edu.enableotg");
                    break;
                case "command_bluetooth":
                    // 6.10无蓝牙edu广播,DeviceOwner走DPM限制清理
                    if (isDeviceOwnerActive()) {
                        enableBluetooth();
                    }
                    break;
                case "command_release_control":
                    Toast.makeText(mContext, "6.10+已移除本地解控后门,此命令仅剩云端通道", Toast.LENGTH_LONG).show();
                    break;
                default:
                    // command_reset_factory等由调用方的dPm.wipeData降级逻辑兜底
                    break;
            }
        } catch (Throwable ignore) {
        }
    }

    @Override
    public void hack_into_generic_mdm_with_Linspirer() {
        super.hack_into_generic_mdm_with_Linspirer();
        // enablePhoneAndSms在6.10拆分;no_control_*解除管控
        edu("com.linspirer.edu.enable_phone");
        edu("com.linspirer.edu.enable_sms");
        edu("com.linspirer.edu.no_control_phone");
        edu("com.linspirer.edu.no_control_sms");
        // 严格管控会触发6.10的广播闸门,DeviceOwner时自动尝试改为宽松
        if (isDeviceOwnerActive()) {
            hw_relaxControlMode();
        }
    }

    @Override
    public void thirdParty_HideApps(ArrayList<String> HideApp) {
        Toast.makeText(mContext, "6.10+已移除三方桌面隐藏广播接口", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void killApplicationProcess(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return;
        }
        try {
            Intent intent = new Intent("com.linspirer.edu.killapp");
            intent.setPackage("com.android.launcher3");
            intent.putExtra("packageName", packageName);
            ArrayList<String> one = new ArrayList<>();
            one.add(packageName);
            intent.putStringArrayListExtra("packageNames", one);
            mContext.sendBroadcast(intent);
        } catch (Throwable ignore) {
        }
    }

    /**
     * 读取华为管控模式: 0普通/1宽松/2严格,-1表示无此API或读取失败.
     * 领创6.10在模式2(严格)下封锁全部第三方edu广播
     */
    public int hw_getControlMode() {
        try {
            Class<?> cls = Class.forName("com.huawei.android.app.admin.DeviceControlManager");
            Object mgr = cls.getDeclaredConstructor().newInstance();
            Method m = cls.getMethod("getControlMode", ComponentName.class);
            Object ret = m.invoke(mgr, new Object[]{null});
            return (Integer) ret;
        } catch (Throwable first) {
            try {
                Class<?> cls = Class.forName("com.huawei.android.app.admin.HwDevicePolicyManager");
                Object mgr = cls.getDeclaredConstructor().newInstance();
                Method m = cls.getMethod("getControlMode", ComponentName.class);
                Object ret = m.invoke(mgr, new Object[]{null});
                return (Integer) ret;
            } catch (Throwable ignore) {
                return -1;
            }
        }
    }

    /**
     * 解除严格管控(需本app为DeviceOwner): 反射调用华为隐藏API
     * DeviceControlManager.setControlMode(admin, 1),模式改为宽松后
     * 领创6.10的edu广播闸门恢复放行
     */
    public boolean hw_relaxControlMode() {
        if (!isDeviceOwnerActive()) {
            return false;
        }
        try {
            Class<?> cls = Class.forName("com.huawei.android.app.admin.DeviceControlManager");
            Object mgr = cls.getDeclaredConstructor().newInstance();
            Method m = cls.getMethod("setControlMode", ComponentName.class, int.class);
            m.invoke(mgr, admin, 1);
            return true;
        } catch (Throwable first) {
            try {
                Class<?> cls = Class.forName("com.huawei.android.app.admin.HwDevicePolicyManager");
                Object mgr = cls.getDeclaredConstructor().newInstance();
                Method m = cls.getMethod("setControlMode", ComponentName.class, int.class);
                m.invoke(mgr, admin, 1);
                return true;
            } catch (Throwable ignore) {
                return false;
            }
        }
    }
}
