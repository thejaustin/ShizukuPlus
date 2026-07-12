#!/bin/bash
gh api \
  --method PATCH \
  -H "Accept: application/vnd.github+json" \
  -H "X-GitHub-Api-Version: 2022-11-28" \
  /repos/thejaustin/ShizukuPlus/issues/comments/4644860417 \
  -f body='@AJ14314 to follow up on your reported issues:

1. **Feature Hub UI overlapping** has been completely fixed! The layout boundaries and overlapping headers were corrected.
2. For now, it is best to hide both to be safe, but we are looking into consolidating this.
3. The **Activity Log and Diagnostics crashes** have been completely fixed! They were failing due to improperly registered activities and manifest conflicts, which have now been resolved.

Also, to everyone tracking the core discovery issue:
We finally isolated why third-party apps were failing to detect Shizuku+.

Because third-party apps check for the specific `moe.shizuku.privileged.api` package, they were failing early. The `compat` app was designed to spoof this, but unfortunately, it was failing to install due to a permission group conflict (error -126). We just stripped those conflicting permission declarations out, which means the `compat` app can now be installed flawlessly. Once you install the updated `compat` dummy app alongside Shizuku+, third-party apps will pass their checks and everything will route perfectly through the Shizuku+ server.

Take a look at the commit that resolves all of this here: https://github.com/thejaustin/ShizukuPlus/commit/fbaecec8

*— Antigravity & thejaustin*'
