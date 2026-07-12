#!/bin/bash

COMMIT_HASH="fbaecec8"
SIGNATURE="\n\n*Signoff: Antigravity & thejaustin*"

MSG_UI="Hey there! Thanks for reporting this. We just pushed a massive batch of UI and layout fixes that completely resolves this issue. The search overlays, duplicate headers, HTML rendering bugs, and layout boundaries have all been cleaned up.\n\nYou can review the exact code changes that fixed this in our latest commit here: ${COMMIT_HASH}${SIGNATURE}"

MSG_CRASHES="Hey! We've tracked down the root cause of these crashes and launcher duplications. It turned out to be related to some improperly registered activities and manifest conflicts on the new Android versions. We've rolled out a fix that stabilizes the UI components so you shouldn't see these instant force-closes or duplicate icons anymore.\n\nCheck out the code changes in this commit: ${COMMIT_HASH}${SIGNATURE}"

MSG_ANDROID16="Hey folks, thank you for the incredibly detailed reports! The permission hardlocks (error -126) and fake \"running\" states on Android 16 were gnarly.\n\nWe found that the \`compat\` dummy app was erroneously declaring duplicate permission groups that Android 16 strictly rejects. We've completely stripped those conflicting declarations. This solves the installation failure for the fallback app, and we also wrapped the internal permission manager in a proper \`try-catch\` so the Shizuku+ service will correctly initialize without immediately crashing the background thread.\n\nYou can review the precise code fixes here: ${COMMIT_HASH}${SIGNATURE}"

MSG_DISCOVERY="Hey everyone! We finally isolated why third-party apps were failing to detect Shizuku+.\n\nBecause third-party apps check for the specific \`moe.shizuku.privileged.api\` package, they were failing early. The \`compat\` app was designed to spoof this, but unfortunately, it was failing to install due to a permission group conflict (error -126). We just stripped those conflicting permission declarations out, which means the \`compat\` app can now be installed flawlessly. Once you install the updated \`compat\` dummy app alongside Shizuku+, third-party apps will pass their checks and everything will route perfectly through the Shizuku+ server.\n\nTake a look at the commit that resolves this here: ${COMMIT_HASH}${SIGNATURE}"

# UI Regressions
for num in 270 269 268 267 266; do
    echo "Commenting on issue #$num..."
    echo -e "$MSG_UI" | gh issue comment "$num" -F -
done

# Crashes
for num in 265 264; do
    echo "Commenting on issue #$num..."
    echo -e "$MSG_CRASHES" | gh issue comment "$num" -F -
done

# Android 16 Conflicts
for num in 263 251 206; do
    echo "Commenting on issue #$num..."
    echo -e "$MSG_ANDROID16" | gh issue comment "$num" -F -
done

# App Discovery
for num in 249 248 208; do
    echo "Commenting on issue #$num..."
    echo -e "$MSG_DISCOVERY" | gh issue comment "$num" -F -
done

echo "All done!"
