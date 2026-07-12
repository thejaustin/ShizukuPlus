import os
import re

LAYOUT_DIR = 'manager/src/main/res/layout'

def fix_diagram(content):
    if '<RelativeLayout' not in content:
        return content
    
    content = re.sub(
        r'<RelativeLayout[^>]*>',
        r'<LinearLayout\n            android:layout_width="match_parent"\n            android:layout_height="wrap_content"\n            android:orientation="horizontal"\n            android:gravity="center_vertical">',
        content
    )
    content = content.replace('</RelativeLayout>', '</LinearLayout>')
    
    content = re.sub(r'\s+android:layout_alignParentStart="true"', '', content)
    content = re.sub(r'\s+android:layout_alignParentEnd="true"', '', content)
    content = re.sub(r'\s+android:layout_centerVertical="true"', '', content)
    content = re.sub(r'\s+android:layout_centerInParent="true"', '', content)
    content = re.sub(r'\s+android:layout_toEndOf="[^"]+"', '', content)
    content = re.sub(r'\s+android:layout_toStartOf="[^"]+"', '', content)
    
    def replace_line(match):
        line = match.group(0)
        if 'android:layout_height="4dp"' in line and 'android:layout_width="match_parent"' in line:
            line = line.replace('android:layout_width="match_parent"', 'android:layout_width="0dp"\n                android:layout_weight="1"')
        return line
        
    content = re.sub(r'<View[^>]+>', replace_line, content)
    
    return content

def fix_ui_header(content):
    # Change FrameLayout width to wrap_content and add weight=1
    def replace_frame(match):
        frame = match.group(0)
        if 'android:id="@+id/mock_icon_container' in frame:
            # We want to keep them 64dp, but maybe just reduce margins
            frame = frame.replace('android:layout_marginHorizontal="12dp"', 'android:layout_marginHorizontal="4dp"')
        return frame
    content = re.sub(r'<FrameLayout[^>]+>', replace_frame, content)
    return content

for f in os.listdir(LAYOUT_DIR):
    if ('diagram' in f or 'header' in f) and f.endswith('.xml'):
        path = os.path.join(LAYOUT_DIR, f)
        with open(path, 'r') as file:
            content = file.read()
        
        new_content = content
        if 'diagram' in f:
            new_content = fix_diagram(content)
        if 'ui_settings_header' in f:
            new_content = fix_ui_header(new_content)
        
        if new_content != content:
            with open(path, 'w') as file:
                file.write(new_content)
            print(f"Fixed {f}")
