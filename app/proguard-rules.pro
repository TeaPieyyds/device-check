# Shizuku 反射调用相关，保留必要类
-keep class rikka.shizuku.** { *; }
-keepclassmembers class rikka.shizuku.** { *; }

# 本应用数据模型，反射/序列化安全
-keep class com.teapieyyds.devicecheck.model.** { *; }
