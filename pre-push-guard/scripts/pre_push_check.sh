#!/bin/bash

# Shizuku+ Pre-Push Validation Script
# Automated checks for common build-breaking issues.

COLOR_RED='\033[0;31m'
COLOR_GREEN='\033[0;32m'
COLOR_YELLOW='\033[0;33m'
COLOR_RESET='\033[0m'

ERRORS=0

echo -e "${COLOR_YELLOW}Running Shizuku+ Pre-Push Guard...${COLOR_RESET}"

# 1. Check CMake Version
echo -n "[1/6] Checking CMake version... "
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
echo -n "[2/6] Checking Java/Kotlin Interop (INSTANCE)... "
INTEROP_FAIL=$(grep -rn "BuildUtils\." server/src/main/java | grep -v "BuildUtils.INSTANCE" | grep ".java:")
if [ ! -z "$INTEROP_FAIL" ]; then
    echo -e "${COLOR_RED}FAIL${COLOR_RESET}"
    echo "$INTEROP_FAIL"
    ERRORS=$((ERRORS + 1))
else
    echo -e "${COLOR_GREEN}PASS${COLOR_RESET}"
fi

# 3. Check for Duplicate Strings
echo -n "[3/6] Checking for duplicate resource keys... "
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
echo -n "[4/6] Checking for missing R imports in Kotlin... "
# Find Kotlin files using R but not importing af.shizuku.manager.R
# Specifically look for R.layout, R.id, etc. and ignore android.R
MISSING_R=$(grep -rl "[^a-zA-Z]R\.[a-z]" manager/src/main/java --include="*.kt" | xargs grep -L "import af.shizuku.manager.R" | grep -v "android.R")
if [ ! -z "$MISSING_R" ]; then
    echo -e "${COLOR_RED}FAIL${COLOR_RESET}"
    echo "$MISSING_R"
    ERRORS=$((ERRORS + 1))
else
    echo -e "${COLOR_GREEN}PASS${COLOR_RESET}"
fi

# 5. Check for Ambiguous Bundle Imports
echo -n "[5/6] Checking for ambiguous Bundle imports... "
AMBIGUOUS_BUNDLE=$(grep -rl "import android.os.Bundle" . | xargs grep -c "import android.os.Bundle" | grep -v ":1$" | grep -v ":0$")
if [ ! -z "$AMBIGUOUS_BUNDLE" ]; then
    echo -e "${COLOR_RED}FAIL${COLOR_RESET}"
    echo "$AMBIGUOUS_BUNDLE"
    ERRORS=$((ERRORS + 1))
else
    echo -e "${COLOR_GREEN}PASS${COLOR_RESET}"
fi

# 6. Check for Missing Coroutine Imports
echo -n "[6/9] Checking for missing Coroutine imports... "
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
echo -n "[7/9] Checking for missing lifecycleScope imports... "
MISSING_LIFECYCLESCOPE=$(grep -rl "lifecycleScope" manager/src/main/java --include="*.kt" | xargs grep -L "import androidx.lifecycle.lifecycleScope" 2>/dev/null)
if [ ! -z "$MISSING_LIFECYCLESCOPE" ]; then
    echo -e "${COLOR_RED}FAIL${COLOR_RESET}"
    echo "$MISSING_LIFECYCLESCOPE"
    ERRORS=$((ERRORS + 1))
else
    echo -e "${COLOR_GREEN}PASS${COLOR_RESET}"
fi

# 8. Check for Misplaced Imports (Syntax Error Prevention)
echo -n "[8/9] Checking for misplaced imports in Kotlin... "
# This checks if the word 'import ' appears after 'class ' or 'object ' in the same file
MISPLACED_IMPORTS=$(awk 'FNR==1 {flag=0} /^import / {if(flag) print FILENAME ":" FNR} /^(class|object|interface) / {flag=1}' $(find manager/src/main/java -name "*.kt" -type f))
if [ ! -z "$MISPLACED_IMPORTS" ]; then
    echo -e "${COLOR_RED}FAIL${COLOR_RESET} (Imports must be at the top of the file)"
    echo "$MISPLACED_IMPORTS"
    ERRORS=$((ERRORS + 1))
else
    echo -e "${COLOR_GREEN}PASS${COLOR_RESET}"
fi

# 9. Check Submodule Sync Status
echo -n "[9/9] Checking submodule remote sync status... "
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
echo -n "[10/12] Checking for hardcoded package names in XML resources... "
HARDCODED_PKG=$(grep -rn "af.shizuku.plus.api:" manager/src/main/res --include="*.xml" 2>/dev/null)
if [ ! -z "$HARDCODED_PKG" ]; then
    echo -e "${COLOR_RED}FAIL${COLOR_RESET} (Avoid hardcoding the package name in resources)"
    echo "$HARDCODED_PKG"
    ERRORS=$((ERRORS + 1))
else
    echo -e "${COLOR_GREEN}PASS${COLOR_RESET}"
fi

# 11. Check for Unresolved colorPrimary in Kotlin
echo -n "[11/12] Checking for unresolved colorPrimary... "
# Sometimes colorPrimary is used directly without R.attr or context.getColor
COLOR_PRIMARY=$(grep -rn "\bcolorPrimary\b" manager/src/main/java --include="*.kt" | grep -v "R.attr" | grep -v "R.color" | grep -v "var " | grep -v "val ")
if [ ! -z "$COLOR_PRIMARY" ]; then
    echo -e "${COLOR_YELLOW}WARN${COLOR_RESET} (Check if colorPrimary is properly referenced)"
    echo "$COLOR_PRIMARY"
else
    echo -e "${COLOR_GREEN}PASS${COLOR_RESET}"
fi

# 12. Check for printStackTrace leftovers
echo -n "[12/14] Checking for printStackTrace leftovers... "
STACK_TRACE=$(grep -rn "printStackTrace" . | grep ".kt:\|.java:")
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
echo -n "[13/14] Checking for stale JNI class paths (moe/shizuku)... "
STALE_JNI=$(grep -rn "moe/shizuku" manager/src/main/jni/ 2>/dev/null)
if [ ! -z "$STALE_JNI" ]; then
    echo -e "${COLOR_RED}FAIL${COLOR_RESET} (Old package path found in JNI — update FindClass() calls to af/shizuku/...)"
    echo "$STALE_JNI"
    ERRORS=$((ERRORS + 1))
else
    echo -e "${COLOR_GREEN}PASS${COLOR_RESET}"
fi

# 14. Check that JniSmokeTest exists (guards against accidental deletion)
echo -n "[14/14] Checking JniSmokeTest exists... "
SMOKE_TEST="manager/src/androidTest/java/af/shizuku/manager/JniSmokeTest.kt"
if [ ! -f "$SMOKE_TEST" ]; then
    echo -e "${COLOR_RED}FAIL${COLOR_RESET} (JniSmokeTest.kt is missing — do not delete the JNI smoke test)"
    ERRORS=$((ERRORS + 1))
else
    echo -e "${COLOR_GREEN}PASS${COLOR_RESET}"
fi

echo -e "\n${COLOR_YELLOW}----------------------------------------${COLOR_RESET}"
if [ $ERRORS -eq 0 ]; then
    echo -e "${COLOR_GREEN}Success: Codebase looks stable for push.${COLOR_RESET}"
    exit 0
else
    echo -e "${COLOR_RED}Failure: $ERRORS critical issues found.${COLOR_RESET}"
    exit 1
fi
