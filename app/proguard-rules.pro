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

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# hostOf()/removeDeadInstance()/removeByHost() reach getHost() through
# Class.getMethod("getHost"): the member NAME must survive minification,
# or dead-instance pruning and the health/latency memory key on null.
-keepclassmembers class io.github.gohoski.notpipe.api.** {
    public java.lang.String getHost();
    public java.lang.String getName();
}
