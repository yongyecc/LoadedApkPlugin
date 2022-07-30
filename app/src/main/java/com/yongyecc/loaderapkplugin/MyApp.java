package com.yongyecc.loaderapkplugin;

import android.app.Application;
import android.content.Context;

import me.weishu.reflection.Reflection;

public class MyApp extends Application {

    public static boolean isHookFinish = false;
    private static Context mApp;

    public static Context getInstance(){
        return mApp;
    }


    /**
     * 目的: 加载APK插件里面的Activity。
     *
     * 1. 将APK实例化成一个LoadedApk对象，载入主线程。
     * 2. 创建一个壳Activity作为插件内Activity的伪装身份。
     * 3. 动态代理IActivityTaskManager，hook startActivity方法，用宿主应用的包名和壳Actvity替换并保存intent对象的包名和Activity信息。
     * 4. 动态代理，hook mH的回调函数，恢复被宿主包名和壳Actvity替换并保存的属于插件Actvity的intent对象。
     * @param base
     */
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        mApp = this;
        Reflection.unseal(base);

//        new Thread(new Runnable() {
//            @Override
//            public void run() {
//                try {
//                    HookManager.getInstance(mApp).loadPluginAPk();
//                } catch (Exception e) {
//                    e.printStackTrace();
//                }
//            }
//        }).start();

        try{
            HookManager.getInstance(mApp).loadPluginAPk();
            HookManager.getInstance(mApp).hookAMSAction();
            HookManager.getInstance(mApp).hookActivityThreadmH();
            isHookFinish = true;
        }catch (Exception e){
            e.printStackTrace();
        }


    }

    @Override
    public void onCreate() {
        super.onCreate();

    }
}
