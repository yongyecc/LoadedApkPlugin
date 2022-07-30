package com.yongyecc.loaderapkplugin;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;

public class utils {

    /**
     * save assets file into local.
     * @param assetsPath
     * @param destPath
     */
    public static void dumpFile(String assetsPath, String destPath){
        File destFile = new File(destPath);
        if(!new File(destFile.getParent()).exists())
            new File(destFile.getParent()).mkdirs();
        try {
            if(!destFile.exists())
                destFile.createNewFile();
            InputStream in =  MyApp.getInstance().getAssets().open(assetsPath);
            FileOutputStream out = new FileOutputStream(destFile);
            byte[] tmpbt = new byte[1024];
            int readCount = 0;
            while((readCount=in.read(tmpbt)) != -1){
                out.write(tmpbt, 0, readCount);
            }
            in.close();
            out.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static ApplicationInfo getAppInfo(File file) throws Exception {
        /*
            执行此方法获取 ApplicationInfo
            public static ApplicationInfo generateApplicationInfo(Package p, int flags,PackageUserState state)
         */
        Class<?> mPackageParserClass = Class.forName("android.content.pm.PackageParser");
        Class<?> mPackageClass = Class.forName("android.content.pm.PackageParser$Package");
        Class<?> mPackageUserStateClass = Class.forName("android.content.pm.PackageUserState");
        //获取 generateApplicationInfo 方法
        Method generateApplicationInfoMethod = mPackageParserClass.getDeclaredMethod("generateApplicationInfo",
                mPackageClass, int.class, mPackageUserStateClass);

        //创建 PackageParser 实例
        Object mmPackageParser = mPackageParserClass.newInstance();

        //获取 Package 实例
        /*
            执行此方法获取一个 Package 实例
            public Package parsePackage(File packageFile, int flags)
         */
        //获取 parsePackage 方法
        Method parsePackageMethod = mPackageParserClass.getDeclaredMethod("parsePackage", File.class, int.class);
        //执行 parsePackage 方法获取 Package 实例
        Object mPackage = parsePackageMethod.invoke(mmPackageParser, file, PackageManager.GET_ACTIVITIES);
        //执行 generateApplicationInfo 方法，获取 ApplicationInfo 实例
        ApplicationInfo applicationInfo = (ApplicationInfo) generateApplicationInfoMethod.invoke(null, mPackage, 0,
                mPackageUserStateClass.newInstance());
        //我们获取的 ApplicationInfo 默认路径是没有设置的，我们要自己设置
        // applicationInfo.sourceDir = 插件路径;
        // applicationInfo.publicSourceDir = 插件路径;
        applicationInfo.sourceDir = file.getAbsolutePath();
        applicationInfo.publicSourceDir = file.getAbsolutePath();
        return applicationInfo;
    }
}
