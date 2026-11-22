#!/usr/bin/env python3
"""Review/refresh machine drafts for the core settings screens with the Gemini CLI (`agyp`).
Usage: review_important.py <locale> "<Language name>" [chunk=30]
Scope: every string referenced by settings_main/behavior/advanced/root_integration/personalization.
- machine draft or missing -> reviewed/translated, written to strings_machine_translated.xml
- human translation (strings.xml) -> never changed; suspected problems go to a report line only.
Context given to the model: this locale's human translations (terminology), the English text."""
import sys, re, json, subprocess, os, glob
from i18n_lib import RES, base_strings, parse, placeholders, fix_escapes
loc, lang = sys.argv[1], sys.argv[2]
chunk = int(sys.argv[3]) if len(sys.argv) > 3 else 30
SCREENS = ['main', 'behavior', 'advanced', 'root_integration', 'personalization']
keys = set()
for s in SCREENS:
    keys |= set(re.findall(r'@string/([A-Za-z0-9_]+)', open(os.path.join(RES, 'xml', f'settings_{s}.xml'), encoding='utf-8').read()))
base = base_strings()
d = os.path.join(RES, 'values-' + loc)
human = {}
for f in glob.glob(os.path.join(d, '*.xml')):
    if not f.endswith('strings_machine_translated.xml'):
        human.update({k: v[0] for k, v in parse(f).items()})
mfile = os.path.join(d, 'strings_machine_translated.xml')
machine = {k: v[0] for k, v in parse(mfile).items()} if os.path.exists(mfile) else {}
todo = sorted(k for k in keys if k in base and k not in human)
glossary = {k: {'en': base[k], loc: human[k]} for k in sorted(keys & human.keys()) if k in base}
gloss = json.dumps(dict(list(glossary.items())[:25]), ensure_ascii=False)
PROMPT = ("You are a native %s localiser reviewing the settings UI of Shizuku+ (an Android Shizuku manager). "
          "Input JSON: key -> {en, current}. 'current' may be empty (translate) or an existing machine draft (fix mistranslations, "
          "unnatural phrasing, inconsistent terms; keep it if already good). Reference human translations for terminology: %s . "
          "Keep EXACT placeholders (%%1$s, %%d, ^1), HTML tags, CDATA wrappers, \\n and \\' escapes; leave brand/technical terms "
          "(Shizuku, Shizuku+, ADB, Wi-Fi, TCP, root, rish, Sentry, GitHub, AMOLED) untranslated. Prefer concise settings-style wording. "
          "Output ONLY a JSON object key -> final translation.")
changed = new = rejected = 0
for i in range(0, len(todo), chunk):
    part = todo[i:i + chunk]
    payload = json.dumps({k: {'en': base[k], 'current': machine.get(k, '')} for k in part}, ensure_ascii=False)
    out = ''
    for _ in range(3):
        r = subprocess.run(['agyp', PROMPT % (lang, gloss)], input=payload, capture_output=True, text=True)
        out = r.stdout + r.stderr
        if 'RESOURCE_EXHAUSTED' in out:
            print('Gemini quota exhausted - re-run later'); sys.exit(2)
        m = re.search(r'\{.*\}', r.stdout, re.S)
        try:
            tr = json.loads(m.group(0)); break
        except Exception:
            tr = None
    if tr is None:
        print('chunk failed', i); continue
    for k, v in tr.items():
        if k not in part or not isinstance(v, str) or not v.strip():
            continue
        v = fix_escapes(v)
        if placeholders(v) != placeholders(base[k]):
            rejected += 1; continue
        if k in machine and machine[k] != v: changed += 1
        elif k not in machine: new += 1
        machine[k] = v
hdr = f'''<?xml version="1.0" encoding="utf-8"?>
<!--
  MACHINE-ASSISTED TRANSLATIONS ({lang}) - NEEDS NATIVE REVIEW.
  Drafted with AI assistance to fill missing strings. If you are a native speaker, please review
  and correct these, then move each corrected string into strings.xml (delete it here: a key
  must live in only one file). Thank you! See TRANSLATING.md.
-->
<resources>
'''
open(mfile, 'w', encoding='utf-8').write(hdr + ''.join(f'    <string name="{k}">{v}</string>\n' for k, v in machine.items()) + '</resources>\n')
print(json.dumps({'locale': loc, 'reviewed': len(todo), 'changed': changed, 'new': new, 'rejected': rejected}))
