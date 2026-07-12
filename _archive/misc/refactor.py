import os
import glob

def refactor():
    layout_dir = '/data/data/com.termux/files/home/ShizukuPlus/manager/src/main/res/layout'
    files = glob.glob(os.path.join(layout_dir, '*.xml'))
    count = 0
    for file in files:
        with open(file, 'r', encoding='utf-8') as f:
            content = f.read()
        
        if 'com.google.android.material.button.MaterialButton' in content:
            # We must be careful not to replace MaterialButtonToggleGroup
            content = content.replace('<com.google.android.material.button.MaterialButton\n', '<af.shizuku.core.ui.compose.ComposeButtonView\n')
            content = content.replace('<com.google.android.material.button.MaterialButton ', '<af.shizuku.core.ui.compose.ComposeButtonView ')
            content = content.replace('</com.google.android.material.button.MaterialButton>', '</af.shizuku.core.ui.compose.ComposeButtonView>')
            
            with open(file, 'w', encoding='utf-8') as f:
                f.write(content)
            count += 1
            print(f"Updated {file}")
            
    print(f"Refactored {count} files.")

if __name__ == '__main__':
    refactor()
