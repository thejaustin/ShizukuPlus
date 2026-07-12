#!/bin/sh

gh issue comment 208 --body "@AJ14314 Thanks for the report. I've dug into these and pushed an update to fix everything:

1. **Feature Hub UI is broken**: The overlapping headers and layout boundaries have been completely fixed!
2. **Hidden Packages feature is unclear**: I updated the settings description to make this much clearer. If you are using both OG Shizuku and Shizuku+, you should add BOTH (\`moe.shizuku.privileged.api\` and \`af.shizuku.manager\`) to the list to prevent conflicts and ensure apps can't detect either of them.
3. **Activity Log and Diagnostics cause crashes**: Fixed! The blank white page was caused by the app failing to apply the proper Material theme to those specific pages during fragment loading. They now render perfectly.

Take a look at the code changes that resolve these in commit https://github.com/thejaustin/ShizukuPlus/commit/be0412ef.

*— Antigravity & thejaustin*"

gh issue comment 264 --body "To clear up the confusion about the \"two Shizuku+ apps\" in the app drawer: 

Our \`AndroidManifest.xml\` accidentally had a duplicate \`LAUNCHER\` intent filter. We had one for the main app activity, and a second one for an internal shortcut (\`activity-alias\`). Because both declared the \`LAUNCHER\` category, Android OS was placing two identical Shizuku+ icons in the app drawer!

I've completely stripped the duplicate \`LAUNCHER\` category, so moving forward, only a single app icon will appear. All the other UI and white-screen bugs you encountered have been fixed as well!

You can see the code changes here: https://github.com/thejaustin/ShizukuPlus/commit/fbaecec8 and https://github.com/thejaustin/ShizukuPlus/commit/be0412ef.

*— Antigravity & thejaustin*"

gh issue comment 204 --body "If the pairing button appears to do nothing, it's likely because Android blocked the notification. The pairing service posts the pairing code as a notification, so if notifications are blocked for the app, the Pair button silently fails.

We've implemented a fallback mechanism: if notifications are disabled, the app will now gracefully skip the tutorial and show an in-app manual pairing dialog instead. You can then enter the IP, port, and code directly from Developer Options.

Take a look at the code changes here: https://github.com/thejaustin/ShizukuPlus/commit/be0412ef

*— Antigravity & thejaustin*"

gh issue comment 237 --body "Good news! An explicit \"Remove Device Owner\" button has been added. 

You no longer need to factory reset to escape Device Owner mode. Once Shizuku+ is set as Device Owner, a \"Clear Device Owner\" option will automatically become visible under \`Settings -> Core Framework -> Device Owner Tools\`. Tapping it will cleanly relinquish the Device Owner privileges.

Take a look at the code changes here: https://github.com/thejaustin/ShizukuPlus/commit/be0412ef

*— Antigravity & thejaustin*"

