import os

with open('manager/src/main/java/af/shizuku/manager/ShizukuSettings.java', 'r') as f:
    content = f.read()

if 'public static boolean isLiveActivityEnabled()' not in content:
    content = content.replace('public static void setLiveActivityEnabled(boolean enable) {',
                              'public static boolean isLiveActivityEnabled() {\n        SharedPreferences p = getPreferences();\n        return p != null && p.getBoolean(Keys.KEY_LIVE_ACTIVITY_ENABLED, false);\n    }\n\n    public static void setLiveActivityEnabled(boolean enable) {')

with open('manager/src/main/java/af/shizuku/manager/ShizukuSettings.java', 'w') as f:
    f.write(content)
