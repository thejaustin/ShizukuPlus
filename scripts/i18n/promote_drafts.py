#!/usr/bin/env python3
"""Remove machine drafts that a real translation now covers.

A key must live in only one of strings.xml / strings_companion.xml / strings_machine_translated.xml
(a duplicate is an aapt error). When Crowdin returns a translation for a key that still has a draft
in values-*/strings_machine_translated.xml, the translation wins and the draft is deleted. Drafts
for keys Crowdin has nothing for are left alone. Prints one line per locale it changed."""
import glob, os, re

RES = os.path.join(os.path.dirname(__file__), '..', '..', 'manager', 'src', 'main', 'res')
NAME = re.compile(r'<(?:string|plurals|string-array)\s+name="([^"]+)"')
DRAFT = re.compile(r'[ \t]*<string name="([^"]+)"[^>]*>.*?</string>[ \t]*\n', re.S)


def keys(path):
    if not os.path.exists(path):
        return set()
    return set(NAME.findall(open(path, encoding='utf-8').read()))


def main():
    total = 0
    for draft_path in sorted(glob.glob(os.path.join(RES, 'values-*', 'strings_machine_translated.xml'))):
        d = os.path.dirname(draft_path)
        real = keys(os.path.join(d, 'strings.xml')) | keys(os.path.join(d, 'strings_companion.xml')) | keys(os.path.join(d, 'arrays.xml'))
        text = open(draft_path, encoding='utf-8').read()
        gone = []

        def drop(m):
            if m.group(1) in real:
                gone.append(m.group(1))
                return ''
            return m.group(0)

        new = DRAFT.sub(drop, text)
        if gone:
            open(draft_path, 'w', encoding='utf-8').write(new)
            total += len(gone)
            print(f'{os.path.basename(d)}: dropped {len(gone)} draft(s) now covered by a translation')
    print(f'{total} draft(s) promoted')


if __name__ == '__main__':
    main()
