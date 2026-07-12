import os
import re

main_xml = 'manager/src/main/res/xml/settings_main.xml'
dev_xml = 'manager/src/main/res/xml/settings_developer_options.xml'

# 1. Read settings_main.xml, extract category_dangerous_experimental and remove it
with open(main_xml, 'r') as f:
    main_content = f.read()

pattern = r'\s*<af\.shizuku\.manager\.settings\.CollapsiblePreferenceCategory[^>]+?key="category_dangerous_experimental".*?</af\.shizuku\.manager\.settings\.CollapsiblePreferenceCategory>'
match = re.search(pattern, main_content, flags=re.DOTALL)
if match:
    dangerous_block = match.group(0)
    main_content = main_content.replace(dangerous_block, '')
    with open(main_xml, 'w') as f:
        f.write(main_content)
    
    # 2. Add it to settings_developer_options.xml before </PreferenceScreen>
    with open(dev_xml, 'r') as f:
        dev_content = f.read()
    dev_content = dev_content.replace('</PreferenceScreen>', dangerous_block + '\n\n</PreferenceScreen>')
    with open(dev_xml, 'w') as f:
        f.write(dev_content)
    print("Moved category_dangerous_experimental to developer options.")

# 3. Remove all DiagramPreference and UiSettingsHeaderPreference blocks from xml
import glob
xml_files = glob.glob('manager/src/main/res/xml/*.xml')

pattern_diagram = r'\s*<af\.shizuku\.manager\.settings\.[a-zA-Z0-9_]*DiagramPreference[^>]*/>'
pattern_header = r'\s*<af\.shizuku\.manager\.settings\.UiSettingsHeaderPreference[^>]*/>'

for xml in xml_files:
    with open(xml, 'r') as f:
        content = f.read()
    
    new_content = re.sub(pattern_diagram, '', content, flags=re.DOTALL)
    new_content = re.sub(pattern_header, '', new_content, flags=re.DOTALL)
    
    if new_content != content:
        with open(xml, 'w') as f:
            f.write(new_content)
        print(f"Removed visuals from {xml}")

