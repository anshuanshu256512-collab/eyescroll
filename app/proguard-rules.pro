-keep class com.eyescroll.** { *; }
-keepclassmembers class com.eyescroll.EyeOverlayService$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
