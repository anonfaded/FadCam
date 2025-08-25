# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Aggressive optimization settings
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*,!code/allocation/variable
-optimizationpasses 7
-allowaccessmodification
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Keep generic signature of TypeToken and its subclasses with R8 version 3.0 and higher
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# For Gson specific classes
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**

# Application classes that will be serialized/deserialized over Gson
-keep class com.fadcam.model.** { <fields>; }
-keep class com.fadcam.data.** { <fields>; }
-keep class com.fadcam.trash.** { <fields>; }

# Keep only necessary resources - this is critical for icons to display properly
# Instead of keeping all resources, keep only what's needed
-keep class **.R$drawable { *; }
-keep class **.R$mipmap { *; }
-keep class **.R$id { *; }
-keep class **.R$menu { *; }
-keep class **.R$layout { *; }
-keep class **.R$string { *; }
-keep class **.R$style { *; }
-keep class **.R$styleable { *; }
-keep class **.R$color { *; }
-keep class **.R$dimen { *; }
-keep class **.R$attr { *; }
-keep class **.R$bool { *; }
-keep class **.R$integer { *; }
-keep class **.R$array { *; }
-keep class **.R$raw { *; }
-keep class **.R$xml { *; }

# FFmpeg specific rules - more aggressive
-keep class com.arthenica.ffmpegkit.FFmpegKit { *; }
-keep class com.arthenica.ffmpegkit.FFmpegKitConfig { *; }
-keep class com.arthenica.ffmpegkit.FFprobeKit { *; }
-keep class com.arthenica.ffmpegkit.MediaInformation { *; }
-keep class com.arthenica.ffmpegkit.MediaInformationSession { *; }
-keep class com.arthenica.ffmpegkit.ReturnCode { *; }
-keep class com.arthenica.ffmpegkit.Session { *; }
-keep class com.arthenica.ffmpegkit.SessionState { *; }
-keep class com.arthenica.ffmpegkit.Statistics { *; }
-keep class com.arthenica.smartexception.java.Exceptions { *; }
-dontwarn com.arthenica.ffmpegkit.**
-keep class com.fadcam.utils.FFmpegUtil { *; }

# Keep only essential drawable resources
-keep public class * extends android.graphics.drawable.Drawable {
    public <init>(...);
}

# Keep only essential view methods
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
}

# Preserve all native method names and the names of their classes
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep only essential AndroidX components
-keep class androidx.appcompat.widget.** { *; }
-keep class androidx.appcompat.app.** { *; }
-keep class androidx.core.widget.** { *; }
-keep class androidx.fragment.app.** { *; }
-keep class androidx.recyclerview.widget.** { *; }
-keep class androidx.viewpager2.widget.** { *; }
-keep class androidx.constraintlayout.widget.** { *; }
-keep class androidx.coordinatorlayout.widget.** { *; }
-keep class androidx.swiperefreshlayout.widget.** { *; }
-keep class androidx.camera.** { *; }
-dontwarn androidx.**

# Keep Material components - only essential ones
-keep class com.google.android.material.bottomsheet.** { *; }
-keep class com.google.android.material.bottomnavigation.** { *; }
-keep class com.google.android.material.navigation.** { *; }
-keep class com.google.android.material.floatingactionbutton.** { *; }
-keep class com.google.android.material.snackbar.** { *; }
-keep class com.google.android.material.tabs.** { *; }
-dontwarn com.google.android.material.**

# Preserve attributes for Android runtime - only essential ones
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep Lottie animations - only essential classes
-keep class com.airbnb.lottie.LottieAnimationView { *; }
-keep class com.airbnb.lottie.LottieDrawable { *; }
-keep class com.airbnb.lottie.LottieComposition { *; }
-keep class com.airbnb.lottie.LottieCompositionFactory { *; }
-keep class com.airbnb.lottie.LottieResult { *; }
-dontwarn com.airbnb.lottie.**

# Keep ExoPlayer - only essential classes
-keep class com.google.android.exoplayer2.ExoPlayer { *; }
-keep class com.google.android.exoplayer2.SimpleExoPlayer { *; }
-keep class com.google.android.exoplayer2.ui.** { *; }
-keep class com.google.android.exoplayer2.source.** { *; }
-keep class com.google.android.exoplayer2.extractor.** { *; }
-keep class com.google.android.exoplayer2.upstream.** { *; }
-keep class com.google.android.exoplayer2.decoder.** { *; }
-keep class com.google.android.exoplayer2.Format { *; }
-keep class com.google.android.exoplayer2.MediaItem { *; }
-dontwarn com.google.android.exoplayer2.**

# Keep OSMDroid - only essential classes
-keep class org.osmdroid.views.** { *; }
-keep class org.osmdroid.tileprovider.** { *; }
-keep class org.osmdroid.util.** { *; }
-dontwarn org.osmdroid.**

# Remove Android logging code
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

# Remove System.out/err
-assumenosideeffects class java.io.PrintStream {
    public void println(...);
    public void print(...);
}

# Keep application activities, services, etc.
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# Specifically keep FadCam's MainActivity and its inner classes (for aliases)
-keep public class com.fadcam.MainActivity { *; }
-keep public class com.fadcam.MainActivity$* { *; }

# Also keep other essential activities and your Application class
-keep public class com.fadcam.SplashActivity { *; }
-keep public class com.fadcam.ui.OnboardingActivity { *; }
-keep public class com.fadcam.FadCamApplication { *; }

# Aggressive optimization settings
-repackageclasses ''
-flattenpackagehierarchy ''
-mergeinterfacesaggressively