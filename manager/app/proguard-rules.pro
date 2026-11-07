# Protobuf
-shrinkunusedprotofields

# Commons-compress
-dontwarn com.github.luben.zstd.**

# ===== Evil 强化配置 =====
# 重打包：所有类塞进同一包，配合自定义字典让人读不出结构
-repackageclasses 'o'
-allowaccessmodification
-overloadaggressively
-classobfuscationdictionary ../obf-classes.txt
-obfuscationdictionary ../obf-members.txt

# 去掉源码信息与行号，崩溃栈里只有混淆名
-renamesourcefileattribute S
-keepattributes SourceFile

# 移除所有日志调用（release 包不留调试痕迹）
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
    public static *** println(...);
}

# JNI 入口：native 方法所在类名不能变（libkernelsu.so 按类名查找）
-keep class me.weishu.kernelsu.Natives { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# 反射/序列化用到的模型（保留字段名）
-keepclassmembers class me.weishu.kernelsu.rules.Rule { *; }
-keepclassmembers class me.weishu.kernelsu.stealth.* { <fields>; }

# 保留组件名（Manifest 引用）
-keep class me.weishu.kernelsu.ui.MainActivity { *; }
-keep class me.weishu.kernelsu.stealth.StealthSettingsActivity { *; }
-keep class me.weishu.kernelsu.stealth.BootReceiver { *; }
-keep class me.weishu.kernelsu.rules.ScriptTriggerActivity { *; }
