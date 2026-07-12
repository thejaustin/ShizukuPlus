const fs = require('fs');

let dev = fs.readFileSync('manager/src/main/res/xml/settings_developer_options.xml', 'utf8');
dev = dev.replace('android:icon="@drawable/ic_restore_24"', 'android:icon="@drawable/ic_history_24"');

const blockMatch = dev.match(/(\s*<af\.shizuku\.manager\.settings\.CollapsiblePreferenceCategory\s+android:key="category_dangerous_experimental"[\s\S]*?<\/af\.shizuku\.manager\.settings\.CollapsiblePreferenceCategory>)/);
if (blockMatch) {
    let block = blockMatch[1];
    dev = dev.replace(block, '');
    block = block.replace('@drawable/ic_system_update', '@drawable/ic_update_24');

    let main = fs.readFileSync('manager/src/main/res/xml/settings_main.xml', 'utf8');
    main = main.replace(/(\s*<af\.shizuku\.manager\.settings\.CollapsiblePreferenceCategory\s+android:key="category_about")/, block + '\n$1');
    fs.writeFileSync('manager/src/main/res/xml/settings_main.xml', main);
}

fs.writeFileSync('manager/src/main/res/xml/settings_developer_options.xml', dev);
console.log('Fixed');
