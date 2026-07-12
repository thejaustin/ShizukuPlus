const fs = require('fs');

const mainXml = 'manager/src/main/res/xml/settings_main.xml';
const devXml = 'manager/src/main/res/xml/settings_developer_options.xml';

let mainContent = fs.readFileSync(mainXml, 'utf8');

const pattern = /\s*<af\.shizuku\.manager\.settings\.CollapsiblePreferenceCategory[^>]+?key="category_dangerous_experimental"[\s\S]*?<\/af\.shizuku\.manager\.settings\.CollapsiblePreferenceCategory>/;
const match = mainContent.match(pattern);

if (match) {
    const dangerousBlock = match[0];
    mainContent = mainContent.replace(dangerousBlock, '');
    fs.writeFileSync(mainXml, mainContent);
    
    let devContent = fs.readFileSync(devXml, 'utf8');
    devContent = devContent.replace('</PreferenceScreen>', dangerousBlock + '\n\n</PreferenceScreen>');
    fs.writeFileSync(devXml, devContent);
    console.log("Moved category_dangerous_experimental to developer options.");
}

const glob = require('fs').readdirSync;
const xmlDir = 'manager/src/main/res/xml/';
const files = glob(xmlDir).filter(f => f.endsWith('.xml'));

const patternDiagram = /\s*<af\.shizuku\.manager\.settings\.[a-zA-Z0-9_]*DiagramPreference[^>]*\/>/g;
const patternHeader = /\s*<af\.shizuku\.manager\.settings\.UiSettingsHeaderPreference[^>]*\/>/g;

files.forEach(file => {
    const filePath = xmlDir + file;
    let content = fs.readFileSync(filePath, 'utf8');
    let newContent = content.replace(patternDiagram, '');
    newContent = newContent.replace(patternHeader, '');
    if (content !== newContent) {
        fs.writeFileSync(filePath, newContent);
        console.log(`Removed visuals from ${file}`);
    }
});
