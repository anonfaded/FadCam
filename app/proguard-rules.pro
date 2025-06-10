# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

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

# Keep menu resources and drawables
-keep class **.R$drawable
-keep class **.R$menu
-keep class **.R$layout
-keep class **.R$id
-keepclassmembers class **.R$* {
    public static <fields>;
}

# More specific rules for preserving resources referenced in XML
-keepclasseswithmembers class **.R$drawable {
    public static final int ic_*;
}

# Keep all drawable resources
-keep public class * extends android.graphics.drawable.Drawable
-keepclassmembers class * extends android.graphics.drawable.Drawable {
    <init>(...);
}

# Keep all menu-related resources and layouts
-keep public class * extends android.view.Menu
-keep public class * extends android.view.MenuItem
-keep public class * extends android.widget.PopupMenu
-keep public class * extends android.view.ContextMenu

# Keep custom views
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

# Keep all resources referenced from XML layouts
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}

-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Preserve the special static fields of all resource classes in the R class
-keepclassmembers class **.R$* {
    public static <fields>;
}

# Prevent stripping off any resources/attributes referenced in XML
-keep public class * extends android.content.res.Resources$Theme
-keepclassmembers class * extends android.content.res.Resources$Theme {
   <methods>;
}

# Preserve the special static methods that are called by the Android runtime
-keepclassmembers class * {
    void <init>(android.content.Context);
}

# Preserve attributes for Android runtime
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Keep all model classes used with Gson
-keep class com.faded.fadcam.model.** { *; }

# Keep Gson stuff
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Keep R classes - prevents resource stripping issues
-keep class **.R
-keep class **.R$* {
    <fields>;
}

# Keep drawables
-keep class **.R$drawable

# Keep menus
-keep class **.R$menu

# Keep layouts
-keep class **.R$layout

# Keep AndroidX stuff
-keep class androidx.** { *; }
-keep interface androidx.** { *; }
-keep public class * extends androidx.**

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep view stuff
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
}

# Only optimize the app minimally
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 2