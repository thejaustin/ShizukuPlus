const fs = require('fs');
const path = require('path');

const LAYOUT_DIR = 'manager/src/main/res/layout';

function fixDiagram(content) {
    if (!content.includes('<RelativeLayout')) return content;
    
    content = content.replace(/<RelativeLayout[^>]*>/, '<LinearLayout\n            android:layout_width="match_parent"\n            android:layout_height="wrap_content"\n            android:orientation="horizontal"\n            android:gravity="center_vertical">');
    content = content.replace(/<\/RelativeLayout>/, '</LinearLayout>');
    
    content = content.replace(/\s+android:layout_alignParentStart="true"/g, '');
    content = content.replace(/\s+android:layout_alignParentEnd="true"/g, '');
    content = content.replace(/\s+android:layout_centerVertical="true"/g, '');
    content = content.replace(/\s+android:layout_centerInParent="true"/g, '');
    content = content.replace(/\s+android:layout_toEndOf="[^"]+"/g, '');
    content = content.replace(/\s+android:layout_toStartOf="[^"]+"/g, '');
    
    content = content.replace(/<View[^>]+>/g, (match) => {
        if (match.includes('android:layout_height="4dp"') && match.includes('android:layout_width="match_parent"')) {
            return match.replace('android:layout_width="match_parent"', 'android:layout_width="0dp"\n                android:layout_weight="1"');
        }
        return match;
    });
    
    return content;
}

function fixUiHeader(content) {
    return content.replace(/<FrameLayout[^>]+>/g, (match) => {
        if (match.includes('android:id="@+id/mock_icon_container')) {
            return match.replace('android:layout_marginHorizontal="12dp"', 'android:layout_marginHorizontal="4dp"');
        }
        return match;
    });
}

const files = fs.readdirSync(LAYOUT_DIR);
for (const f of files) {
    if ((f.includes('diagram') || f.includes('header')) && f.endsWith('.xml')) {
        const p = path.join(LAYOUT_DIR, f);
        const originalContent = fs.readFileSync(p, 'utf8');
        let newContent = originalContent;
        
        if (f.includes('diagram')) {
            newContent = fixDiagram(newContent);
        }
        if (f.includes('ui_settings_header')) {
            newContent = fixUiHeader(newContent);
        }
        
        if (newContent !== originalContent) {
            fs.writeFileSync(p, newContent);
            console.log(`Fixed ${f}`);
        }
    }
}
