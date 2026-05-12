# Keep DJI Helper in primary DEX so it can be called in attachBaseContext
# before MultiDex.install() loads secondary DEX files.
-keep class com.secneo.sdk.Helper { *; }
