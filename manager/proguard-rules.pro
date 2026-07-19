

-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Keep the CREATOR field on every Parcelable. Android's default ProGuard config
# normally supplies this, but the manager module REPLACES the defaults
# (build.gradle: proguardFiles = ["proguard-rules.pro"]), so R8 was stripping
# CREATOR from obfuscated Parcelables and crashing binder IPC on service start
# with "BadParcelableException: ... no CREATOR" (#298, Android 12).
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# Enums reconstructed by name across Parcel/IPC also lose values()/valueOf()
# when the defaults are dropped; keep them for the same reason.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
	public static void check*(...);
	public static void throw*(...);
}

-assumenosideeffects class java.util.Objects{
    ** requireNonNull(...);
}

# All three BinderContainer variants cross the process boundary by class name in
# the sendBinder Bundle (server -> manager/client). Keep every one's name AND its
# CREATOR intact; keeping only af.shizuku.api left the other two strippable (#298).
-keepnames class af.shizuku.api.BinderContainer
-keepnames class rikka.shizuku.BinderContainer
-keepnames class moe.shizuku.api.BinderContainer
-keep class af.shizuku.api.BinderContainer { *; }
-keep class rikka.shizuku.BinderContainer { *; }
-keep class moe.shizuku.api.BinderContainer { *; }

# Missing class android.app.IProcessObserver$Stub
# Missing class android.app.IUidObserver$Stub
-keepclassmembers class rikka.hidden.compat.adapter.ProcessObserverAdapter {
    <methods>;
}

-keepclassmembers class rikka.hidden.compat.adapter.UidObserverAdapter {
    <methods>;
}

# Entrance of Shizuku service
-keep class rikka.shizuku.server.ShizukuService {
    public static void main(java.lang.String[]);
}

# Entrance of user service starter
-keep class af.shizuku.starter.ServiceStarter {
    public static void main(java.lang.String[]);
}

# Entrance of shell
-keep class af.shizuku.manager.shell.Shell {
    public static void main(java.lang.String[], java.lang.String, android.os.IBinder, android.os.Handler);
}

# Keep settings fragments instantiated by name via reflection in PreferenceFragmentCompat
-keep public class af.shizuku.manager.settings.** extends androidx.fragment.app.Fragment {
    public <init>();
}

# Keep WorkManager workers instantiated by name via reflection.
# Both RemoteDbSyncWorker and AdbStartWorker must maintain their class names.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Keep Room-generated database implementations. Room discovers these via reflection
# (Class.getDeclaredConstructor()) and R8 strips them without this rule (#293, #288).
-keep class * extends androidx.room.RoomDatabase { <init>(); }

-assumenosideeffects class android.util.Log {
    public static *** d(...);
}

-assumenosideeffects class af.shizuku.manager.utils.Logger {
    public *** d(...);
}

#noinspection ShrinkerUnresolvedReference
-assumenosideeffects class rikka.shizuku.server.util.Logger {
    public *** d(...);
}

# Mavericks: companion-object factories discovered via Kotlin reflection;
# We must keep both the ViewModel and its Factory/Companion to maintain their relationship.
-keep class af.shizuku.manager.**ViewModel { *; }
-keep class af.shizuku.manager.**ViewModel$* { *; }
-keep class af.shizuku.manager.home.HomeViewModel$Companion { *; }
-keep class * implements com.airbnb.mvrx.MavericksViewModelFactory { *; }
-keep class * extends com.airbnb.mvrx.MavericksViewModel { *; }
-keepclassmembers class * extends com.airbnb.mvrx.MavericksViewModel {
    public static ** Companion;
}

-keep interface com.airbnb.mvrx.** { *; }
-keep class com.airbnb.mvrx.** { *; }
-keepnames class com.airbnb.mvrx.** { *; }

# Mavericks state classes (e.g. HomeState) are validated at runtime as immutable Kotlin data
# classes via reflection (assertMavericksDataClassImmutability reads the component/copy members).
# Only ViewModels were kept above, not the MavericksState classes themselves, so R8 could strip
# HomeState's data-class members and break state init with "Mavericks state must be a data class!"
# — crashing the home screen (SHIZUKUPLUS-46). This is Mavericks' documented consumer keep.
-keep class * implements com.airbnb.mvrx.MavericksState { *; }
-keepclassmembers class * implements com.airbnb.mvrx.MavericksState { *; }

# Keep resource IDs and generated R classes to prevent "0_resource_name_obfuscated" crashes with ViewBinding.
-keep class af.shizuku.manager.R$* { *; }
-keepclassmembers class **.R$* {
    public static <fields>;
}

# Custom View subclasses inflated from XML by class name — R8 must not rename or remove them.
-keep class af.shizuku.manager.utils.EmptyStateView { public <init>(android.content.Context, android.util.AttributeSet); }

-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault



# Sentry compose instrumentation references compose-ui which isn't in the classpath
# (project uses Glance only). Suppress missing-class R8 errors for these packages.
-dontwarn io.sentry.compose.**
-dontwarn androidx.compose.**

-allowaccessmodification
#-repackageclasses rikka.shizuku
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
