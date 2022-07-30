package com.yongyecc.loaderapkplugin;

import android.annotation.SuppressLint;
import android.content.AttributionSource;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import dalvik.system.DexClassLoader;

public class HookManager {

    private Context context;
    private static HookManager instance;
    public String TAG = this.getClass().getName();

    private HookManager(Context context) {
        this.context = context;
    }

    public static HookManager getInstance(Context context) {
        if (instance == null) {
            synchronized (HookManager.class) {
                if (instance == null) {
                    instance = new HookManager(context);
                }
            }
        }
        return instance;
    }

    /**
     * 将插件APK实例化成一个LoadedApk对象，并加载进主线程的mPackages字段中。
     *
     * @throws Exception
     */
    public void loadPluginAPk() throws Exception {
        File file = new File(String.format("%s/Plugin.apk",
                MyApp.getInstance().getFilesDir().getAbsolutePath()));
        utils.dumpFile("Plugin.apk", file.getAbsolutePath());
        //获取 ActivityThread 类
        Class<?> mActivityThreadClass = Class.forName("android.app.ActivityThread");
        //获取 ActivityThread 的 currentActivityThread() 方法
        Method currentActivityThread = mActivityThreadClass.getDeclaredMethod("currentActivityThread");
        currentActivityThread.setAccessible(true);
        //获取 ActivityThread 实例
        Object mActivityThread = currentActivityThread.invoke(null);
        //获取 mPackages 属性
        Field mPackagesField = mActivityThreadClass.getDeclaredField("mPackages");
        mPackagesField.setAccessible(true);
        //获取 mPackages 属性的值
        ArrayMap<String, Object> mPackages = (ArrayMap<String, Object>) mPackagesField.get(mActivityThread);


        Class<?> mCompatibilityInfoClass = Class.forName("android.content.res.CompatibilityInfo");
        Method getLoadedApkMethod = mActivityThreadClass.getDeclaredMethod("getPackageInfoNoCheck",
                ApplicationInfo.class, mCompatibilityInfoClass);

        /*
             public static final CompatibilityInfo DEFAULT_COMPATIBILITY_INFO = new CompatibilityInfo() {};
         */
        //以上注释是获取默认的 CompatibilityInfo 实例
        Field mCompatibilityInfoDefaultField = mCompatibilityInfoClass.getDeclaredField("DEFAULT_COMPATIBILITY_INFO");
        Object mCompatibilityInfo = mCompatibilityInfoDefaultField.get(null);

        //获取一个 ApplicationInfo实例
        ApplicationInfo applicationInfo = utils.getAppInfo(file);
//        applicationInfo.uid = context.getApplicationInfo().uid;
        //执行此方法，获取一个 LoadedApk
        Object mLoadedApk = getLoadedApkMethod.invoke(mActivityThread, applicationInfo, mCompatibilityInfo);

        //自定义一个 ClassLoader
        String optimizedDirectory = context.getDir("plugin", Context.MODE_PRIVATE).getAbsolutePath();
        DexClassLoader classLoader = new DexClassLoader(file.getAbsolutePath(), optimizedDirectory,
                null, context.getClassLoader());

        //private ClassLoader mClassLoader;
        //获取 LoadedApk 的 mClassLoader 属性
        Field mClassLoaderField = mLoadedApk.getClass().getDeclaredField("mClassLoader");
        mClassLoaderField.setAccessible(true);
        //设置自定义的 classLoader 到 mClassLoader 属性中
        mClassLoaderField.set(mLoadedApk, classLoader);

        WeakReference loadApkReference = new WeakReference(mLoadedApk);
        //添加自定义的 LoadedApk
        mPackages.put(applicationInfo.packageName, loadApkReference);
        //重新设置 mPackages
        mPackagesField.set(mActivityThread, mPackages);
        Thread.sleep(2000);
    }


    /**
     * 动态代理Activity管理服务的本地Binder对象，拦截应用进程发送给系统服务的startActvity请求，
     * 用宿主应用的包名和壳Actvity替换并保存intent对象的包名和Activity信息。
     * @throws Exception
     */
    @SuppressLint("BlockedPrivateApi")
    public void hookAMSAction() throws Exception{
        //动态代理
        Class<?> mIActivityManagerClass;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mIActivityManagerClass = Class.forName("android.app.IActivityTaskManager");
        } else {
            mIActivityManagerClass = Class.forName("android.app.IActivityManager");
        }
        //获取 ActivityManager 或 ActivityManagerNative 或 ActivityTaskManager
        Class<?> mActivityManagerClass;
        Method getActivityManagerMethod;
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) {
            mActivityManagerClass = Class.forName("android.app.ActivityManagerNative");
            getActivityManagerMethod = mActivityManagerClass.getDeclaredMethod("getDefault");
        } else if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1 && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            mActivityManagerClass = Class.forName("android.app.ActivityManager");
            getActivityManagerMethod = mActivityManagerClass.getDeclaredMethod("getService");
        } else {
            mActivityManagerClass = Class.forName("android.app.ActivityTaskManager");
            getActivityManagerMethod = mActivityManagerClass.getDeclaredMethod("getService");
        }
        getActivityManagerMethod.setAccessible(true);
        //这个实例本质是 IActivityManager或者IActivityTaskManager
        final Object IActivityManager = getActivityManagerMethod.invoke(null);

        //创建动态代理
        Object mActivityManagerProxy = Proxy.newProxyInstance(
                context.getClassLoader(),
                new Class[]{mIActivityManagerClass},//要监听的回调接口
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

                        int paramIndex = 2;
                        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                            paramIndex = 3;

                        if ("startActivity".equals(method.getName())) {
                            Log.d(TAG, "[dynamic proxy] startActivity called.");
                            Log.d(TAG, String.format("[dynamic proxy] intent=%s", args[paramIndex]));
                            //换成可以通过AMS检测的Activity
                            Intent intent = new Intent(context, StubActivity.class);
                            intent.putExtra("actonIntent", (Intent) args[paramIndex]);
                            args[paramIndex] = intent;
                            Log.d(TAG, String.format("[dynamic proxy] after replace intent=%s", args[paramIndex]));
                        }
                        //让程序继续能够执行下去
                        return method.invoke(IActivityManager, args);
                    }
                }
        );

        //获取 IActivityTaskManagerSingleton 或者 IActivityManagerSingleton 或者 gDefault 属性
        Field mSingletonField;
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) {
            mSingletonField = mActivityManagerClass.getDeclaredField("gDefault");
        } else if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1 && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            mSingletonField = mActivityManagerClass.getDeclaredField("IActivityManagerSingleton");
        } else {
            mSingletonField = mActivityManagerClass.getDeclaredField("IActivityTaskManagerSingleton");
        }
        mSingletonField.setAccessible(true);
        Object mSingleton = mSingletonField.get(null);
        //替换点
        Class<?> mSingletonClass = Class.forName(Constans.SINGLETON);
        Field mInstanceField = mSingletonClass.getDeclaredField("mInstance");
        mInstanceField.setAccessible(true);
        //将我们创建的动态代理设置到 mInstance 属性当中
        mInstanceField.set(mSingleton, mActivityManagerProxy);
    }

    public void hookActivityThreadmH() throws Exception{
        //获取 ActivityThread 类
        Class<?> mActivityThreadClass = Class.forName("android.app.ActivityThread");

        //获取 ActivityThread 的 currentActivityThread() 方法
        Method currentActivityThread = mActivityThreadClass.getDeclaredMethod("currentActivityThread");
        currentActivityThread.setAccessible(true);
        //获取 ActivityThread 实例
        Object mActivityThread = currentActivityThread.invoke(null);

        //获取 ActivityThread 的 mH 属性
        Field mHField = mActivityThreadClass.getDeclaredField("mH");
        mHField.setAccessible(true);
        Handler mH = (Handler) mHField.get(mActivityThread);

        //获取 Handler 的 mCallback 属性
        Field mCallbackField = Handler.class.getDeclaredField("mCallback");
        mCallbackField.setAccessible(true);
        //设置我们自定义的 CallBack
        mCallbackField.set(mH, new MyCallBack());
    }

    class MyCallBack implements Handler.Callback {

        @RequiresApi(api = Build.VERSION_CODES.S)
        @Override
        public boolean handleMessage(@NonNull Message msg) {
//            Log.d(TAG, String.format("[mH]handleMessage msg=%s", msg));
            if (msg.what == Constans.EXECUTE_TRANSACTION) {
                try {
                    Field mActivityCallbacksField = msg.obj.getClass().getDeclaredField("mActivityCallbacks");
                    mActivityCallbacksField.setAccessible(true);
                    List<Object> mActivityCallbacks = (List<Object>) mActivityCallbacksField.get(msg.obj);
                    if (mActivityCallbacks != null && mActivityCallbacks.size() > 0) {
                        Object mClientTransactionItem = mActivityCallbacks.get(0);
                        Class<?> mLaunchActivityItemClass = Class.forName("android.app.servertransaction.LaunchActivityItem");
                        if (mLaunchActivityItemClass.isInstance(mClientTransactionItem)) {
                            //获取 LaunchActivityItem 的 mIntent 属性
                            Field mIntentField = mClientTransactionItem.getClass().getDeclaredField("mIntent");
                            mIntentField.setAccessible(true);
                            Intent intent = (Intent) mIntentField.get(mClientTransactionItem);
                            //取出我们传递的值
                            Intent actonIntent = intent.getParcelableExtra("actonIntent");

                            /**
                             * 我们在以下代码中，对插件 和 宿主进行区分
                             */
                            Field mActivityInfoField = mClientTransactionItem.getClass().getDeclaredField("mInfo");
                            mActivityInfoField.setAccessible(true);
                            ActivityInfo mActivityInfo = (ActivityInfo) mActivityInfoField.get(mClientTransactionItem);

                            Log.d(TAG, String.format("[mH]EXECUTE_TRANSACTION intent=%s", intent));
                            if (actonIntent != null) {
                                //替换掉原来的intent属性的值
                                mIntentField.set(mClientTransactionItem, actonIntent);
                                Log.d(TAG, String.format("[mH]EXECUTE_TRANSACTION actonIntent=%s", mIntentField.get(mClientTransactionItem)));
                                //证明是插件
                                if (actonIntent.getPackage() == null) {
                                    mActivityInfo.applicationInfo.packageName = actonIntent.getComponent().getPackageName();
                                    hookGlobalProviderHolder();
                                    hookSystemProviderHolder();
                                    //hook 拦截 getPackageInfo 做自己的逻辑
                                    hookGetPackageInfo();
                                } else {
                                    //宿主的
                                    mActivityInfo.applicationInfo.packageName = actonIntent.getPackage();
                                }
                            }

                            mActivityInfoField.set(mClientTransactionItem, mActivityInfo);
                        }
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }

            } else if (msg.what == Constans.LAUNCH_ACTIVITY) {
                try {
                    //获取 ActivityClientRecord 的 intent 属性
                    Field intentField = msg.obj.getClass().getDeclaredField("intent");
                    intentField.setAccessible(true);
                    Intent intent = (Intent) intentField.get(msg.obj);
                    //取出我们传递的值
                    Intent actonIntent = intent.getParcelableExtra("actonIntent");

                    /**
                     * 我们在以下代码中，对插件 和 宿主进行区分
                     */
                    Field mActivityInfoField = msg.obj.getClass().getDeclaredField("activityInfo");
                    mActivityInfoField.setAccessible(true);
                    ActivityInfo mActivityInfo = (ActivityInfo) mActivityInfoField.get(msg.obj);

                    Log.d(TAG, String.format("[mH]LAUNCH_ACTIVITY intent=%s", intent));
                    Log.d(TAG, String.format("[mH]LAUNCH_ACTIVITY actonIntent=%s", actonIntent));
                    if (actonIntent != null) {
                        //替换掉原来的intent属性的值
                        intentField.set(msg.obj, actonIntent);
                        //证明是插件
                        if (actonIntent.getPackage() == null) {
                            mActivityInfo.applicationInfo.packageName = actonIntent.getComponent().getPackageName();
                            hookGetPackageInfo();
                        } else {
                            //宿主的
                            mActivityInfo.applicationInfo.packageName = actonIntent.getPackage();
                        }
                    }
                    mActivityInfoField.set(msg.obj, mActivityInfo);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            return false;
        }
    }

    /**
     *
     * @throws Exception
     */
    private void hookGetPackageInfo() throws Exception {
        //static volatile IPackageManager sPackageManager;
        //获取 系统的 sPackageManager 把它替换成我们自己的动态代理
        Class<?> mActivityThreadClass = Class.forName("android.app.ActivityThread");
        Field mPackageManager1Field = mActivityThreadClass.getDeclaredField("sPackageManager");
        mPackageManager1Field.setAccessible(true);
        final Object sPackageManager = mPackageManager1Field.get(null);
        Class<?> mIPackageManagerClass = Class.forName("android.content.pm.IPackageManager");

        //实现动态代理
        Object mPackageManagerProxy = Proxy.newProxyInstance(
                context.getClassLoader(),
                new Class[]{mIPackageManagerClass},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if ("getPackageInfo".equals(method.getName())) {
                            //如果是 getPackageInfo 方法才做我们的逻辑
                            //如何才能绕过 PMS检查呢
                            //pi != null
                            //直接返回一个 PackageInfo
                            Log.e(TAG, method.getName());
                            return new PackageInfo();

                        }
                        return method.invoke(sPackageManager, args);
                    }
                }
        );

        //替换 换成我们自己的动态代理
        mPackageManager1Field.set(null, mPackageManagerProxy);

    }

    @SuppressLint("PrivateApi")
    private void hookGlobalProviderHolder() throws Exception{
        Class<?> mIContentProviderClass = Class.forName("android.content.IContentProvider");

        @SuppressLint("SoonBlockedPrivateApi") Field sProviderHolderFiled = Settings.Global.class.getDeclaredField("sProviderHolder");
        sProviderHolderFiled.setAccessible(true);
        Object sProviderHolder = sProviderHolderFiled.get(null);
        Method getProviderMethod = sProviderHolder.getClass().getDeclaredMethod("getProvider", ContentResolver.class);
        getProviderMethod.setAccessible(true);
        final Object iContentProvider = getProviderMethod.invoke(sProviderHolder, context.getContentResolver());
        Field mContentProviderFiled = sProviderHolder.getClass().getDeclaredField("mContentProvider");
        mContentProviderFiled.setAccessible(true);

        Object mContentProviderProxy = Proxy.newProxyInstance(
                context.getClassLoader(),
                new Class[]{mIContentProviderClass},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        for(Object arg: args)
                            Log.d(TAG, String.format("[hookGlobalProviderHolder] arg=%s", arg));
                        if (method.getName().equals("call")) {
                            Log.d(TAG, method.getName());
                            args[0] = context.getPackageName();
                        }
                        return method.invoke(iContentProvider, args);
                    }
                }
        );
        mContentProviderFiled.set(sProviderHolder, mContentProviderProxy);
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private void hookSystemProviderHolder() throws Exception{
        Class<?> mIContentProviderClass = Class.forName("android.content.IContentProvider");

        @SuppressLint("SoonBlockedPrivateApi") Field sProviderHolderFiled = Settings.System.class.getDeclaredField("sProviderHolder");
        sProviderHolderFiled.setAccessible(true);
        Object sProviderHolder = sProviderHolderFiled.get(null);
        Method getProviderMethod = sProviderHolder.getClass().getDeclaredMethod("getProvider", ContentResolver.class);
        getProviderMethod.setAccessible(true);
        final Object iContentProvider = getProviderMethod.invoke(sProviderHolder, context.getContentResolver());
        Field mContentProviderFiled = sProviderHolder.getClass().getDeclaredField("mContentProvider");
        mContentProviderFiled.setAccessible(true);

        Object mContentProviderProxy = Proxy.newProxyInstance(
                context.getClassLoader(),
                new Class[]{mIContentProviderClass},
                new InvocationHandler() {
                    @RequiresApi(api = Build.VERSION_CODES.S)
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        for(Object arg: args)
                            Log.d(TAG, String.format("[hookSystemProviderHolder] arg=%s", arg));
                        if (method.getName().equals("call")) {
                            Log.d(TAG, method.getName());
                            args[0] = context.getPackageName();
                        }
                        return method.invoke(iContentProvider, args);
                    }
                }
        );
        mContentProviderFiled.set(sProviderHolder, mContentProviderProxy);
    }
}
