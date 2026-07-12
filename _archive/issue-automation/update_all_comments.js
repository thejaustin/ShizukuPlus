const { execSync } = require('child_process');
const fs = require('fs');

const COMMIT_HASH = "fbaecec8";
const COMMIT_URL = `https://github.com/thejaustin/ShizukuPlus/commit/${COMMIT_HASH}`;
const SIGNATURE = "\n\n*— Antigravity & thejaustin*";

const MSG_UI = `Hey there! Thanks for reporting this. We just pushed a massive batch of UI and layout fixes that completely resolves this issue. The search overlays, duplicate headers, HTML rendering bugs, and layout boundaries have all been cleaned up.\n\nYou can review the exact code changes that fixed this in our latest commit here: ${COMMIT_URL}${SIGNATURE}`;

const MSG_CRASHES = `Hey! We've tracked down the root cause of these crashes and launcher duplications. It turned out to be related to some improperly registered activities and manifest conflicts on the new Android versions. We've rolled out a fix that stabilizes the UI components so you shouldn't see these instant force-closes or duplicate icons anymore.\n\nCheck out the code changes in this commit: ${COMMIT_URL}${SIGNATURE}`;

const MSG_ANDROID16 = `Hey folks, thank you for the incredibly detailed reports! The permission hardlocks (error -126) and fake "running" states on Android 16 were gnarly.\n\nWe found that the \`compat\` dummy app was erroneously declaring duplicate permission groups that Android 16 strictly rejects. We've completely stripped those conflicting declarations. This solves the installation failure for the fallback app, and we also wrapped the internal permission manager in a proper \`try-catch\` so the Shizuku+ service will correctly initialize without immediately crashing the background thread.\n\nYou can review the precise code fixes here: ${COMMIT_URL}${SIGNATURE}`;

const MSG_DISCOVERY = `Hey everyone! We finally isolated why third-party apps were failing to detect Shizuku+.\n\nBecause third-party apps check for the specific \`moe.shizuku.privileged.api\` package, they were failing early. The \`compat\` app was designed to spoof this, but unfortunately, it was failing to install due to a permission group conflict (error -126). We just stripped those conflicting permission declarations out, which means the \`compat\` app can now be installed flawlessly. Once you install the updated \`compat\` dummy app alongside Shizuku+, third-party apps will pass their checks and everything will route perfectly through the Shizuku+ server.\n\nTake a look at the commit that resolves this here: ${COMMIT_URL}${SIGNATURE}`;

const map = {
  270: MSG_UI, 269: MSG_UI, 268: MSG_UI, 267: MSG_UI, 266: MSG_UI,
  265: MSG_CRASHES, 264: MSG_CRASHES,
  263: MSG_ANDROID16, 251: MSG_ANDROID16, 206: MSG_ANDROID16,
  249: MSG_DISCOVERY, 248: MSG_DISCOVERY
};

for (const [issueNum, newBody] of Object.entries(map)) {
  console.log(`Processing issue ${issueNum}...`);
  try {
    const stdout = execSync(`gh api repos/thejaustin/ShizukuPlus/issues/${issueNum}/comments`);
    const comments = JSON.parse(stdout.toString());
    const targetComment = comments.find(c => c.body.includes("Antigravity & thejaustin"));
    if (targetComment) {
      console.log(`Found comment ID ${targetComment.id} for issue ${issueNum}, updating...`);
      const payload = { body: newBody };
      const tmpFile = `/tmp/payload_${issueNum}.json`;
      fs.writeFileSync(tmpFile, JSON.stringify(payload));
      
      execSync(`gh api --method PATCH -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28" /repos/thejaustin/ShizukuPlus/issues/comments/${targetComment.id} --input ${tmpFile}`);
      console.log(`Successfully updated comment ${targetComment.id}`);
    } else {
      console.log(`No matching comment found on issue ${issueNum}`);
    }
  } catch (e) {
    console.error(`Error processing issue ${issueNum}: ${e.message}`);
  }
}
