# 目的

打开APK插件里面的Activity。

# 技术难题

## 1. 怎么将APK插件加载进主线程？

使用getPackageInfoNoCheck方法，将APK插件实例化成一个LoadedApk对象，然后通过反射将LoadedApk对象放进主线程的LoadedApk列表(mPackages)中。

## 2. 系统服务会检查Activity是否已经注册，怎么通过检查？

1) 在宿主应用的AndroidManifest.xml文件中注册大量壳Activity。
2) 在宿主应用向系统服务发送启动插件Activity的请求时，修改请求中的intent对象，将intent中的插件包名、插件Activity替换为宿主应用的包名和壳Activity，并保存插件包名、插件Activity。

## 3. 宿主应用向系统服务发送插件Activity的startActivity请求时，怎么更换为宿主应用的包名和壳Activity？

1) 动态代理IActivityTaskManager接口，hook startActivity方法，当请求中包含插件Activity时，将插件包名、插件Activity替换为宿主应用的包名和壳Activity。
2) 保存插件包名和插件Activity到intent的附加数据中。

## 4. 系统服务检查完成后，让宿主应用启动壳Activity时，怎么替换为插件Activity？

1) 反射替换掉ActivityThread的mH属性，hook其handleMessage方法，当接收到壳Activity的启动请求时，从intent对象的附加数据中获取插件包名、插件Activity，并替换掉宿主应用的包名和壳Activity。

# 技术解决方案

1. 将APK插件文件实例化出一个LoadedApk对象，并载入用户进程空间。
2. AndroidManifest里注册一些空Activity作为插件内Activity的伪造身份。
3. 动态代理AMS的本地Binder类，给AMS发送请求时，将Intent内部的插件Activity保存并更替为空Activity。
4. 动态代理ActivityThread的mH对象，应用启动Activity时，将Intent内的空Activity替换回插件Activity。


## 1. 实例化APK插件为LoadedApk对象

### 为什么使用getPackageInfoNoCheck方法?

因为这方方法内部实现了LoadedApk对象的创建和载入到ActivityThread对象内的操作。

```java
//1. 实例化LoadedApk对象
packageInfo =
    new LoadedApk(this, aInfo, compatInfo, baseLoader,
            securityViolation, includeCode &&
            (aInfo.flags&ApplicationInfo.FLAG_HAS_CODE) != 0, registerPackage);

.................
} else if (includeCode) {
//2. 导入LoadedApk对象到UI线程内。将LoadedApk对象存放到mPackages列表里。
    mPackages.put(aInfo.packageName,
            new WeakReference<LoadedApk>(packageInfo));
} else {
    mResourcePackages.put(aInfo.packageName,
            new WeakReference<LoadedApk>(packageInfo));
}
```

## 2. 动态代理IActivityTaskManager接口，hook startActivity方法

```java
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
```

## 3. 反射替换mH对象，hook handleMessage方法

```java

public boolean handleMessage(@NonNull Message msg) {
        if (msg.what == Constans.EXECUTE_TRANSACTION) {
            try {
  ..............
                        Log.d(TAG, String.format("[mH]EXECUTE_TRANSACTION intent=%s", intent));
                        if (actonIntent != null) {
                            //替换掉原来的intent属性的值
                            mIntentField.set(mClientTransactionItem, actonIntent);
                            Log.d(TAG, String.format("[mH]EXECUTE_TRANSACTION actonIntent=%s", mIntentField.get(mClientTransactionItem)));
                            //证明是插件
                            if (actonIntent.getPackage() == null) {
                                mActivityInfo.applicationInfo.packageName = actonIntent.getComponent().getPackageName();
                                //bugfix:  package xxx not belong xxx
                                hookGlobalProviderHolder();
                                hookSystemProviderHolder();
                                //bugfix: can not instance xxx
                                hookGetPackageInfo();
                            } else {
                                //宿主的
                                mActivityInfo.applicationInfo.packageName = actonIntent.getPackage();
                            }
                        }

                        mActivityInfoField.set(mClientTransactionItem, mActivityInfo);
                    }
  ..............
}
```

# 术语

项|描述
!--!|--
APK插件|一种APK文件格式的插件。
宿主进程(宿主应用)|加载插件的应用进程。
插件Activity|插件内的Activity。
壳Activity|作为插件Activity的

# 问题

【1】Android12 上，ActivityThread启动Actvitiy失败。

# 参考

[1] [Android插件化探索(三)LoadedApk式插件化 - 掘金](https://juejin.cn/post/6893740587678187528)
[2] [Activity的启动过程详解(基于Android10.0) - 掘金](https://juejin.cn/post/6847902222294990862)
[3] [Android如何绕过反射黑名单](https://ljd1996.github.io/2021/02/07/Android%E5%A6%82%E4%BD%95%E7%BB%95%E8%BF%87%E5%8F%8D%E5%B0%84%E9%BB%91%E5%90%8D%E5%8D%95/)






