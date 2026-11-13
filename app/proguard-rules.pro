# Shizuku UserService 由 Shizuku 服务端按类名反射加载，必须保留（含两个构造）。
-keep class com.bd.tts.shizuku.RemoteFileService { *; }
-keep class com.bd.tts.IRemoteFileService { *; }
-keep class com.bd.tts.IRemoteFileService$Stub { *; }
-keep class com.bd.tts.IRemoteFileService$Stub$Proxy { *; }
-keep class rikka.shizuku.** { *; }
