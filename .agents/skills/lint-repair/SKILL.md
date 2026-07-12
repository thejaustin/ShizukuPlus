---
name: lint-repair
description: Automates running Android Lint and repairing errors/warnings in the codebase.
license: Complete terms in LICENSE
metadata:
  author: Google LLC
  keywords:
  - lint
  - static analysis
  - detekt
  - code smells
---
# Static Analysis (Lint) Repair Skill

This skill automates the execution, parsing, and remediation of Android Lint warnings with minimal context bloat.

## Execution Steps:

1. **Execute Lint:**
   Run the Gradle lint task.
   ```bash
   ./gradlew :manager:lintRelease
   ```
   *Note: Do not read the massive console output. Rely on the generated XML report.*

2. **Parse XML Report Efficiently:**
   The Android lint report can be massive. Instead of reading the whole XML into context, use an inline Python script or `xmllint`/`grep` pipeline to extract ONLY the `severity="Error"` or `severity="Fatal"` issues.
   ```bash
   python3 -c '
   import xml.etree.ElementTree as ET
   tree = ET.parse("manager/build/reports/lint-results-release.xml")
   for issue in tree.getroot().findall("issue"):
       if issue.get("severity") in ["Error", "Fatal"]:
           loc = issue.find("location")
           print(f"{loc.get(\"file\")}:{loc.get(\"line\")} - {issue.get(\"message\")}")
   '
   ```

3. **Contextualize and Fix:**
   Read only the specific lines mentioned in the python output using the `view_file` tool (specifying exact `StartLine` and `EndLine` padding). 
   Use `multi_replace_file_content` to fix the warnings without loading the entire class.
