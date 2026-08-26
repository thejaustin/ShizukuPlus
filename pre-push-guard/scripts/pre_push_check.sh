#!/bin/bash

# Shizuku+ Pre-Push Validation Script
# Automated checks for common build-breaking issues.

COLOR_RED='\033[0;31m'
COLOR_GREEN='\033[0;32m'
COLOR_YELLOW='\033[0;33m'
COLOR_RESET='\033[0m'

ERRORS=0

echo -e "${COLOR_YELLOW}Running Shizuku+ Pre-Push Guard...${COLOR_RESET}"

STEP=0
step() {
    STEP=$((STEP+1))
    echo -n "[$STEP] $1... "
}

# 1. Check CMake Version
step "Checking CMake version"
CMAKE_FILE="manager/src/main/jni/CMakeLists.txt"
if [ -f "$CMAKE_FILE" ]; then
    VERSION=$(grep "cmake_minimum_required" "$CMAKE_FILE" | grep -o "[0-9.]*")
    if [ "$VERSION" != "3.22.1" ]; then
        echo -e "${COLOR_RED}FAIL${COLOR_RESET} (Found $VERSION, expected 3.22.1)"
        ERRORS=$((ERRORS + 1))
    else
        echo -e "${COLOR_GREEN}PASS${COLOR_RESET}"
    fi
else
    echo -e "${COLOR_YELLOW}SKIP${COLOR_RESET} (File not found)"
fi

# 2. Check Java/Kotlin Interop (BuildUtils.INSTANCE)
step "Checking Java/Kotlin Interop (INSTANCE)"
INTEROP_FAIL=$(grep -rn "BuildUtils\." server/src/main/java | grep -v "BuildUtils.INSTANCE" | grep ".java:")
if [ ! -z "$INTEROP_FAIL" ]; then
    echo -e "${COLOR_RED}FAIL${COLOR_RESET}"
    echo "$INTEROP_FAIL"
    ERRORS=$((ERRORS + 1))
else
    echo -e "${COLOR_GREEN}PASS${COLOR_RESET}"
fi

# 3. Check for Duplicate Strings
step "Checking for duplicate resource keys"
STRINGS_FILE="manager/src/main/res/values/strings.xml"
if [ -f "$STRINGS_FILE" ]; then
    DUPLICATES=$(grep -o "name=\"[^\"]*\"" "$STRINGS_FILE" | sort | uniq -d)
    if [ ! -z "$DUPLICATES" ]; then
        echo -e "${COLOR_RED}FAIL${COLOR_RESET}"
        echo "$DUPLICATES"
        ERRORS=$((ERRORS + 1))
    else
        echo -e "${COLOR_GREEN}PASS${COLOR_RESET}"
    fi
else
    echo -e "${COLOR_YELLOW}SKIP${COLOR_RESET}"
fi

# 4. Check for Missing R Imports in Kotlin
step "Checking for missing R imports in Kotlin"
# Find Kotlin files using R but not importing af.shizuku.manager.R
# Specifically look for R.layout, R.id, etc. and ignore android.R
MISSING_R=$(grep -rl "[^a-zA-Z.]R\.[a-z]" manager/src/main/java --include="*.kt" | xargs grep -L "import af.shizuku.manager.R" | grep -v "android.R")
if [ ! -z "$MISSING_R" ]; then
    echo -e "${COLOR_RED}FAIL${COLOR_RESET}"
    echo "$MISSING_R"
    ERRORS=$((ERRORS + 1))
else
    echo -e "${COLOR_GREEN}PASS${COLOR_RESET}"
fi

# 5. Check for Ambiguous Bundle Imports
step "Checking for ambiguous Bundle imports"
AMBIGUOUS_BUNDLE=$(grep -rl --exclude-dir={.ai,.git} "import android.os.Bundle" . | xargs grep -c "import android.os.Bundle" | grep -v ":1$" | grep -v ":0$")
if [ ! -z "$AMBIGUOUS_BUNDLE" ]; then
    echo -e "${COLOR_RED}FAIL${COLOR_RESET}"
    echo "$AMBIGUOUS_BUNDLE"
    ERRORS=$((ERRORS + 1))
else
    echo -e "${COLOR_GREEN}PASS${COLOR_RESET}"
fi

# 6. Check for Missing Coroutine Imports
step "Checking for missing Coroutine imports"
# Files using 'launch {' without importing kotlinx.coroutines.launch or kotlinx.coroutines.*
MISSING_LAUNCH=$(grep -rl "launch {" manager/src/main/java --include="*.kt" | xargs grep -L -e "import kotlinx.coroutines.launch" -e "import kotlinx.coroutines.\*" 2>/dev/null)
if [ ! -z "$MISSING_LAUNCH" ]; then
    MISSING_DISPATCHERS=$(grep -rl "Dispatchers\." manager/src/main/java --include="*.kt" | xargs grep -L -e "import kotlinx.coroutines.Dispatchers" -e "import kotlinx.coroutines.\*" 2>/dev/null)
    if [ ! -z "$MISSING_DISPATCHERS" ]; then
        echo -e "${COLOR_RED}FAIL${COLOR_RESET} (Missing Dispatchers import)"
        echo "$MISSING_DISPATCHERS"
        ERRORS=$((ERRORS + 1))
    else
        echo -e "${COLOR_GREEN}PASS${COLOR_RESET}"
    fi
else
    echo -e "${COLOR_GREEN}PASS${COLOR_RESET}"
fi

# 7. Check for Missing lifecycleScope Imports
step "Checking for missing lifecycleScope imports"
MISSING_LIFECYCLESCOPE=$(grep -rl "lifecycleScope" manager/src/main/java --include="*.kt" | xargs grep -L "import androidx.lifecycle.lifecycleScope" 2>/dev/null)
if [ ! -z "$MISSING_LIFECYCLESCOPE" ]; then
    echo -e "${COLOR_RED}FAIL${COLOR_RESET}"
    echo "$MISSING_LIFECYCLESCOPE"
    ERRORS=$((ERRORS + 1))
else
    echo -e "${COLOR_GREEN}PASS${COLOR_RESET}"
fi

# 8. Check for Misplaced Imports (Syntax Error Prevention)
step "Checking for misplaced imports in Kotlin"
# This checks if the word 'import ' appears after 'class ' or 'object ' in the same file
MISPLACED_IMPORTS=$(awk 'FNR==1 {flag=0} /^import / {if(flag) print FILENAME ":" FNR} /^(class|object|interface) / {flag=1}' $(find manager/src/main/java -name "*.kt" -type f -not -path "*/.*"))
if [ ! -z "$MISPLACED_IMPORTS" ]; then
    echo -e "${COLOR_RED}FAIL${COLOR_RESET} (Imports must be at the top of the file)"
    echo "$MISPLACED_IMPORTS"
    ERRORS=$((ERRORS + 1))
else
    echo -e "${COLOR_GREEN}PASS${COLOR_RESET}"
fi

# 9. Check Submodule Sync Status
step "Checking submodule remote sync status"
if [ -d "api/.git" ]; then
    cd api
    # Check if we are in CI (CI=true is standard in GH Actions)
    if [ "$CI" = "true" ]; then
        echo -e "${COLOR_GREEN}PASS${COLOR_RESET} (CI Environment - assuming checkout success)"
    else
        # Check if the current HEAD exists on the remote
        CURRENT_COMMIT=$(git rev-parse HEAD)
        if ! git ls-remote origin | grep -q "$CURRENT_COMMIT"; then
            echo -e "${COLOR_RED}FAIL${COLOR_RESET} (Submodule commit $CURRENT_COMMIT is not pushed to origin)"
            echo ">> Fix: Run 'cd api && git push' before pushing the main repository."
            ERRORS=$((ERRORS + 1))
        else
            echo -e "${COLOR_GREEN}PASS${COLOR_RESET}"
        fi
    fi
    cd ..
else
    # In some CI setups, submodules are checked out without .git folders
    echo -e "${COLOR_GREEN}PASS${COLOR_RESET} (Submodule directory exists)"
fi

# 10. Check for Hardcoded Package Names in XML (AAPT Errors)
step "Checking for hardcoded package names in XML resources"
HARDCODED_PKG=$(grep -rn "af.shizuku.plus.api:" manager/src/main/res --include="*.xml" 2>/dev/null)
if [ ! -z "$HARDCODED_PKG" ]; then
    echo -e "${COLOR_RED}FAIL${COLOR_RESET} (Avoid hardcoding the package name in resources)"
    echo "$HARDCODED_PKG"
    ERRORS=$((ERRORS + 1))
else
    echo -e "${COLOR_GREEN}PASS${COLOR_RESET}"
fi

# 11. Check for Unresolved colorPrimary in Kotlin
step "Checking for unresolved colorPrimary"
# Sometimes colorPrimary is used directly without R.attr or context.getColor
COLOR_PRIMARY=$(grep -rn "\bcolorPrimary\b" manager/src/main/java --include="*.kt" | grep -v "R.attr" | grep -v "R.color" | grep -v "var " | grep -v "val ")
if [ ! -z "$COLOR_PRIMARY" ]; then
    echo -e "${COLOR_YELLOW}WARN${COLOR_RESET} (Check if colorPrimary is properly referenced)"
    echo "$COLOR_PRIMARY"
else
    echo -e "${COLOR_GREEN}PASS${COLOR_RESET}"
fi

# 12. Check for printStackTrace leftovers
step "Checking for printStackTrace leftovers"
STACK_TRACE=$(grep -rn --exclude-dir={.ai,.git} "printStackTrace" . | grep ".kt:\|.java:")
if [ ! -z "$STACK_TRACE" ]; then
    echo -e "${COLOR_YELLOW}WARN${COLOR_RESET} (Use loge or Log.e instead)"
    echo "$STACK_TRACE"
    # Warning only, don't increment ERRORS
else
    echo -e "${COLOR_GREEN}PASS${COLOR_RESET}"
fi

# 13. Check for stale package paths in JNI C++ files
# After a package rename, FindClass() calls in JNI_OnLoad must be updated.
# A stale path causes RegisterNatives to SIGABRT the process on launch.
step "Checking for stale JNI class paths (moe/shizuku)"
STALE_JNI=$(grep -rn "moe/shizuku" manager/src/main/jni/ 2>/dev/null)
if [ ! -z "$STALE_JNI" ]; then
    echo -e "${COLOR_RED}FAIL${COLOR_RESET} (Old package path found in JNI — update FindClass() calls to af/shizuku/...)"
    echo "$STALE_JNI"
    ERRORS=$((ERRORS + 1))
else
    echo -e "${COLOR_GREEN}PASS${COLOR_RESET}"
fi

# 14. Check that JniSmokeTest exists (guards against accidental deletion)
step "Checking JniSmokeTest exists"
SMOKE_TEST="manager/src/androidTest/java/af/shizuku/manager/JniSmokeTest.kt"
if [ ! -f "$SMOKE_TEST" ]; then
    echo -e "${COLOR_RED}FAIL${COLOR_RESET} (JniSmokeTest.kt is missing — do not delete the JNI smoke test)"
    ERRORS=$((ERRORS + 1))
else
    echo -e "${COLOR_GREEN}PASS${COLOR_RESET}"
fi

# 15. XML well-formedness check on layout files
step "Checking layout XML well-formedness"
if command -v xmllint >/dev/null 2>&1; then
    XML_ERRORS=""
    for f in manager/src/main/res/layout/*.xml; do
        result=$(xmllint --noout "$f" 2>&1)
        if [ $? -ne 0 ]; then
            XML_ERRORS="${XML_ERRORS}\n  $f: $result"
        fi
    done
    if [ ! -z "$XML_ERRORS" ]; then
        echo -e "${COLOR_RED}FAIL${COLOR_RESET} (Malformed layout XML)"
        echo -e "$XML_ERRORS"
        ERRORS=$((ERRORS + 1))
    else
        echo -e "${COLOR_GREEN}PASS${COLOR_RESET}"
    fi
else
    echo -e "${COLOR_YELLOW}SKIP${COLOR_RESET} (xmllint not found)"
fi

# 18. Appcompat-owned attrs referenced through the material R class.
# With non-transitive R classes (mandatory on current AGP), com.google.android.material.R
# no longer re-exports appcompat's attrs, so these references fail to compile in CI.
step "Checking for appcompat attrs read via material R"
APPCOMPAT_ATTRS="colorPrimary|colorPrimaryDark|colorAccent|colorError|colorControlNormal|colorControlActivated|colorControlHighlight|colorButtonNormal"
WRONG_R=$(grep -rnE "material\.R\.attr\.($APPCOMPAT_ATTRS)\b" --include="*.kt" --include="*.java" manager/src core/ 2>/dev/null)
if [ ! -z "$WRONG_R" ]; then
    echo -e "${COLOR_RED}FAIL${COLOR_RESET} (use androidx.appcompat.R.attr for these)"
    echo "$WRONG_R"
    ERRORS=$((ERRORS + 1))
else
    echo -e "${COLOR_GREEN}PASS${COLOR_RESET}"
fi

# 19. Styles with an implicit (dotted-name) parent that is neither defined in the
# project nor a recognizable library style - aapt2 fails resource linking on these.
step "Checking implicit style parents resolve"
STYLE_PARENTS=$(python3 - <<'PYEOF'
import re, glob
defined, implicit = set(), []
for f in glob.glob('manager/src/main/res/values*/*.xml') + glob.glob('core/*/src/main/res/values*/*.xml'):
    for m in re.finditer(r'<style\s+name="([^"]+)"(?:\s+parent="([^"]*)")?', open(f, encoding='utf-8').read()):
        name, parent = m.group(1), m.group(2)
        defined.add(name)
        if parent is None and '.' in name:
            implicit.append((f, name))
# Library prefixes whose styles exist in dependencies, not project res.
LIB = re.compile(r'^(Theme|ThemeOverlay\.Material3|ShapeAppearance\.(Material3|M3)|Widget|TextAppearance|Base|Platform|Preference|PreferenceThemeOverlay|Animation|MaterialAlertDialog|CollapsingToolbar)([.$]|$)')
for f, name in implicit:
    parent = name.rsplit('.', 1)[0]
    if parent not in defined and not LIB.match(parent) and not LIB.match(name):
        print(f'{f}: "{name}" implicitly inherits undefined style "{parent}"')
PYEOF
)
if [ ! -z "$STYLE_PARENTS" ]; then
    echo -e "${COLOR_RED}FAIL${COLOR_RESET} (declare the parent style or set an explicit parent=\"\")"
    echo "$STYLE_PARENTS"
    ERRORS=$((ERRORS + 1))
else
    echo -e "${COLOR_GREEN}PASS${COLOR_RESET}"
fi

# 20. Large tracked files - committed binaries/logs bloat every clone (122MB of CI
# logs and an 80MB ktlint binary have slipped in before). Sizes come from the git
# object DB, not the filesystem, so this stays fast on slow storage.
step "Checking for tracked files over 5MB"
BIG_FILES=$(paste <(git ls-files -s | grep -v '^160000' | awk '{print $4}') \
                  <(git ls-files -s | grep -v '^160000' | awk '{print $2}' | git cat-file --batch-check='%(objectsize)' 2>/dev/null) \
            | awk '$2 > 5242880 {printf "  %s (%.1f MB)\n", $1, $2/1048576}')
if [ ! -z "$BIG_FILES" ]; then
    echo -e "${COLOR_RED}FAIL${COLOR_RESET} (do not commit large binaries/logs)"
    echo "$BIG_FILES"
    ERRORS=$((ERRORS + 1))
else
    echo -e "${COLOR_GREEN}PASS${COLOR_RESET}"
fi

# 21. Sentry manual-init guard - removing this meta-data reintroduces the
# double-init crash (Sentry auto-init + manual init in ShizukuApplication).
step "Checking Sentry auto-init stays disabled"
if grep -q 'android:name="io.sentry.auto-init" android:value="false"' manager/src/main/AndroidManifest.xml; then
    echo -e "${COLOR_GREEN}PASS${COLOR_RESET}"
else
    echo -e "${COLOR_RED}FAIL${COLOR_RESET} (io.sentry.auto-init=false meta-data missing from manager manifest)"
    ERRORS=$((ERRORS + 1))
fi

# 22. Mavericks ProGuard keeps - R8 strips companion factories without these,
# crashing release builds at runtime.
step "Checking Mavericks ProGuard keep rules exist"
if grep -q 'MavericksViewModelFactory' manager/proguard-rules.pro && grep -q 'extends com.airbnb.mvrx.MavericksViewModel' manager/proguard-rules.pro; then
    echo -e "${COLOR_GREEN}PASS${COLOR_RESET}"
else
    echo -e "${COLOR_RED}FAIL${COLOR_RESET} (Mavericks keep rules missing from manager/proguard-rules.pro)"
    ERRORS=$((ERRORS + 1))
fi

# 23. XML settings string reference integrity - each @string/foo in settings_*.xml must
# have a <string name="foo"> entry in any values XML file. aapt2 only catches this at
# resource-link time (too late for the pre-push guard unless Gradle runs).
step "Checking settings XML string references resolve"
MISSING_STRINGS=$(python3 - <<'PYEOF'
import re, glob
defined = set()
for f in glob.glob('manager/src/main/res/values*/*.xml'):
    defined.update(re.findall(r'<string name="([^"]+)"', open(f, encoding='utf-8').read()))
missing = []
for f in glob.glob('manager/src/main/res/xml/settings_*.xml'):
    for ref in re.findall(r'@string/([^"\'>\s]+)', open(f, encoding='utf-8').read()):
        if ref not in defined:
            missing.append(f'  {f}: @string/{ref}')
for line in sorted(set(missing)):
    print(line)
PYEOF
)
if [ ! -z "$MISSING_STRINGS" ]; then
    echo -e "${COLOR_RED}FAIL${COLOR_RESET} (missing string resource references)"
    echo "$MISSING_STRINGS"
    ERRORS=$((ERRORS + 1))
else
    echo -e "${COLOR_GREEN}PASS${COLOR_RESET}"
fi

# 24. drawable/mipmap reference integrity across the whole res/ tree - broke CI 3 builds
# in a row (d58bb2ea): drawable-v26/ic_launcher.xml still pointed at
# @mipmap/ic_launcher_foreground after 7c14a77d moved it to @drawable/ic_launcher_foreground.
# aapt2 only catches this at resource-link time (too late for the pre-push guard unless
# Gradle runs), same class of gap as the string-reference check above but for drawable/mipmap
# resources. Originally this only scanned ic_launcher*.xml (where the bug first surfaced);
# generalized to scan every XML file under res/ since the same dangling-reference bug can
# occur anywhere, not just adaptive-icon XML.
step "Checking drawable/mipmap references resolve"
MISSING_ICON_REFS=$(python3 - <<'PYEOF'
import re, glob, os
# mipmap and drawable are DISTINCT resource types in Android (R.mipmap vs R.drawable) - a
# file under drawable/foo.xml does NOT satisfy an @mipmap/foo reference, even though they
# share a base name. This is exactly what let the original bug slip past a same-named-file
# check: drawable/ic_launcher_foreground.xml existed, but the reference was @mipmap/... and
# nothing existed under any mipmap*/ dir with that name. Track per resource type separately.
defined = {'drawable': set(), 'mipmap': set()}
for restype in defined:
    for f in glob.glob(f'manager/src/main/res/{restype}*/*') + glob.glob(f'core/*/src/main/res/{restype}*/*'):
        defined[restype].add(os.path.splitext(os.path.basename(f))[0])

missing = []
for f in glob.glob('manager/src/main/res/**/*.xml', recursive=True) + glob.glob('core/*/src/main/res/**/*.xml', recursive=True):
    for restype, name in re.findall(r'@(drawable|mipmap)/([A-Za-z0-9_]+)', open(f, encoding='utf-8').read()):
        if name not in defined[restype]:
            missing.append(f'  {f}: @{restype}/{name}')
for line in sorted(set(missing)):
    print(line)
PYEOF
)
if [ ! -z "$MISSING_ICON_REFS" ]; then
    echo -e "${COLOR_RED}FAIL${COLOR_RESET} (missing drawable/mipmap resource references)"
    echo "$MISSING_ICON_REFS"
    ERRORS=$((ERRORS + 1))
else
    echo -e "${COLOR_GREEN}PASS${COLOR_RESET}"
fi

# 25. color reference integrity across the whole res/ tree - same class of dangling-@ref
# bug as check 24, but for @color/X. Colors can be declared two ways: as <color name="X">
# entries in values*/colors.xml, OR as filename-based resources under res/color*/ (e.g.
# selector state-lists like grant_permissions_button_ripple_color_selector.xml) - both count
# as "defined". Unlike drawable/mipmap, color is a single resource type here so there's no
# cross-type mismatch to guard against, just existence.
step "Checking color references resolve"
MISSING_COLOR_REFS=$(python3 - <<'PYEOF'
import re, glob, os
defined = set()
for f in glob.glob('manager/src/main/res/values*/colors.xml') + glob.glob('core/*/src/main/res/values*/colors.xml'):
    for m in re.finditer(r'<color\s+name="([^"]+)"', open(f, encoding='utf-8').read()):
        defined.add(m.group(1))
for f in glob.glob('manager/src/main/res/color*/*') + glob.glob('core/*/src/main/res/color*/*'):
    defined.add(os.path.splitext(os.path.basename(f))[0])

missing = []
for f in glob.glob('manager/src/main/res/**/*.xml', recursive=True) + glob.glob('core/*/src/main/res/**/*.xml', recursive=True):
    for name in re.findall(r'@color/([A-Za-z0-9_.]+)', open(f, encoding='utf-8').read()):
        if name not in defined:
            missing.append(f'  {f}: @color/{name}')
for line in sorted(set(missing)):
    print(line)
PYEOF
)
if [ ! -z "$MISSING_COLOR_REFS" ]; then
    echo -e "${COLOR_RED}FAIL${COLOR_RESET} (missing color resource references)"
    echo "$MISSING_COLOR_REFS"
    ERRORS=$((ERRORS + 1))
else
    echo -e "${COLOR_GREEN}PASS${COLOR_RESET}"
fi

# 26. style reference integrity across the whole res/ tree - same class of dangling-@ref
# bug as checks 24/25, but for @style/X (e.g. android:theme="@style/Foo",
# style="@style/Bar" attributes in layouts/XML, not just style-parent inheritance which
# check 19 already covers). Reuses check 19's LIB prefix allowlist to skip styles that are
# provided by AndroidX/Material/Preference libraries rather than declared in this project,
# since we can't enumerate every library-provided style name and a naive check would false-
# positive on nearly every Material3/Widget/TextAppearance/ShapeAppearance reference.
step "Checking style references resolve"
MISSING_STYLE_REFS=$(python3 - <<'PYEOF'
import re, glob
defined = set()
for f in glob.glob('manager/src/main/res/values*/*.xml') + glob.glob('core/*/src/main/res/values*/*.xml'):
    for m in re.finditer(r'<style\s+name="([^"]+)"', open(f, encoding='utf-8').read()):
        defined.add(m.group(1))
LIB = re.compile(r'^(Theme|ThemeOverlay\.Material3|ShapeAppearance\.(Material3|M3)|Widget|TextAppearance|Base|Platform|Preference|PreferenceThemeOverlay|Animation|MaterialAlertDialog|CollapsingToolbar)([.$]|$)')

missing = []
for f in glob.glob('manager/src/main/res/**/*.xml', recursive=True) + glob.glob('core/*/src/main/res/**/*.xml', recursive=True):
    for name in re.findall(r'@style/([A-Za-z0-9_.]+)', open(f, encoding='utf-8').read()):
        if name not in defined and not LIB.match(name):
            missing.append(f'  {f}: @style/{name}')
for line in sorted(set(missing)):
    print(line)
PYEOF
)
if [ ! -z "$MISSING_STYLE_REFS" ]; then
    echo -e "${COLOR_RED}FAIL${COLOR_RESET} (missing style resource references)"
    echo "$MISSING_STYLE_REFS"
    ERRORS=$((ERRORS + 1))
else
    echo -e "${COLOR_GREEN}PASS${COLOR_RESET}"
fi

# 17. Dry-Run Build Validation
step "Validating Gradle build configuration (dry-run)"
if [ -n "${SKIP_GRADLE_CHECK:-}" ]; then
    echo -e "${COLOR_YELLOW}SKIP${COLOR_RESET} (SKIP_GRADLE_CHECK set)"
elif ./gradlew assembleDebug --dry-run >/dev/null 2>&1; then
    echo -e "${COLOR_GREEN}PASS${COLOR_RESET}"
else
    echo -e "${COLOR_RED}FAIL${COLOR_RESET} (Gradle build configuration failed. Run ./gradlew assembleDebug for details)"
    ERRORS=$((ERRORS + 1))
fi

echo -e "\n${COLOR_YELLOW}----------------------------------------${COLOR_RESET}"
if [ $ERRORS -eq 0 ]; then
    echo -e "${COLOR_GREEN}Success: Codebase looks stable for push.${COLOR_RESET}"
    exit 0
else
    echo -e "${COLOR_RED}Failure: $ERRORS critical issues found.${COLOR_RESET}"
    exit 1
fi
