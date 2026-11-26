# R8 / ProGuard rules for the release build.

# WorkManager instantiates Workers reflectively from their class name, so the
# class and its constructor must survive minification/obfuscation.
-keep class com.smarttubeupdater.UpdateWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Keep line numbers so obfuscated crash reports stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin lambdas / coroutines rely on these attributes on some code paths.
-keepattributes InnerClasses,EnclosingMethod,Signature,*Annotation*
