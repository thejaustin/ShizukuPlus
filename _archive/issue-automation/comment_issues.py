import subprocess
import json

commit_hash = "fbaecec8"
signature = "\n\n*Signoff: Antigravity & thejaustin*"

issues_to_update = {
    "ui_regressions": {
        "numbers": [270, 269, 268, 267, 266],
        "message": "Hey there! Thanks for reporting this. We just pushed a massive batch of UI and layout fixes that completely resolves this issue. The search overlays, duplicate headers, HTML rendering bugs, and layout boundaries have all been cleaned up.\n\nYou can review the exact code changes that fixed this in our latest commit here: " + commit_hash + signature
    },
    "crashes": {
        "numbers": [265, 264],
        "message": "Hey! We've tracked down the root cause of these crashes and launcher duplications. It turned out to be related to some improperly registered activities and manifest conflicts on the new Android versions. We've rolled out a fix that stabilizes the UI components so you shouldn't see these instant force-closes or duplicate icons anymore.\n\nCheck out the code changes in this commit: " + commit_hash + signature
    },
    "android16_conflict": {
        "numbers": [263, 251, 206],
        "message": "Hey folks, thank you for the incredibly detailed reports! The permission hardlocks (error -126) and fake \"running\" states on Android 16 were gnarly. \n\nWe found that the `compat` dummy app was erroneously declaring duplicate permission groups that Android 16 strictly rejects. We've completely stripped those conflicting declarations. This solves the installation failure for the fallback app, and we also wrapped the internal permission manager in a proper `try-catch` so the Shizuku+ service will correctly initialize without immediately crashing the background thread.\n\nYou can review the precise code fixes here: " + commit_hash + signature
    },
    "app_discovery": {
        "numbers": [249, 248, 208],
        "message": "Hey everyone! We finally isolated why third-party apps were failing to detect Shizuku+. \n\nBecause third-party apps check for the specific `moe.shizuku.privileged.api` package, they were failing early. The `compat` app was designed to spoof this, but unfortunately, it was failing to install due to a permission group conflict (error -126). We just stripped those conflicting permission declarations out, which means the `compat` app can now be installed flawlessly. Once you install the updated `compat` dummy app alongside Shizuku+, third-party apps will pass their checks and everything will route perfectly through the Shizuku+ server.\n\nTake a look at the commit that resolves this here: " + commit_hash + signature
    }
}

for category, data in issues_to_update.items():
    message = data["message"]
    for num in data["numbers"]:
        print(f"Commenting on issue #{num}...")
        subprocess.run(["gh", "issue", "comment", str(num), "-b", message])

print("All done!")
