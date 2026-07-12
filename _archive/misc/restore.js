const fs = require('fs');

function insertAfter(file, targetKey, diagramKey, diagramClass) {
    let content = fs.readFileSync(file, 'utf8');
    const regex = new RegExp(`(<(SwitchPreferenceCompat|af.shizuku.manager.settings.PlusFeaturePreference|af.shizuku.manager.settings.AppPickerPreference)[^>]+android:key="${targetKey}"[^>]*/>)`, 'g');
    
    if (content.match(regex)) {
        content = content.replace(regex, `$1\n\n        <${diagramClass}\n            android:key="${diagramKey}" />`);
        fs.writeFileSync(file, content);
        console.log(`Restored ${diagramKey} in ${file}`);
    }
}

function insertHeader(file, afterRegex, diagramKey, diagramClass) {
    let content = fs.readFileSync(file, 'utf8');
    const rx = new RegExp(afterRegex);
    if (content.match(rx) && !content.includes(diagramKey)) {
        content = content.replace(rx, `$1\n    <${diagramClass}\n        android:key="${diagramKey}" />\n`);
        fs.writeFileSync(file, content);
        console.log(`Restored header ${diagramKey} in ${file}`);
    }
}

const pBase = 'manager/src/main/res/xml/';

insertHeader(pBase + 'settings_personalization.xml', '(<!-- Section 1: Visual Design Preview -->)', 'ui_settings_header', 'af.shizuku.manager.settings.UiSettingsHeaderPreference');
insertHeader(pBase + 'settings_ui.xml', '(<PreferenceScreen[^>]*>)', 'ui_settings_header', 'af.shizuku.manager.settings.UiSettingsHeaderPreference');

insertAfter(pBase + 'settings_root_integration.xml', 'su_bridge_enabled', 'su_bridge_diagram', 'af.shizuku.manager.settings.SUBridgeDiagramPreference');

const pPlus = pBase + 'settings_shizuku_plus.xml';
insertAfter(pPlus, 'shadow_binder_enabled', 'shadow_binder_diagram', 'af.shizuku.manager.settings.ShadowBinderDiagramPreference');
insertAfter(pPlus, 'network_governor_plus_enabled', 'network_governor_plus_diagram', 'af.shizuku.manager.settings.DNSGovernorDiagramPreference');
insertAfter(pPlus, 'binder_firewall_enabled', 'binder_firewall_diagram', 'af.shizuku.manager.settings.BinderFirewallDiagramPreference');
insertAfter(pPlus, 'storage_proxy_enabled', 'storage_proxy_diagram', 'af.shizuku.manager.settings.StorageBridgeDiagramPreference');
insertAfter(pPlus, 'vm_manager_enabled', 'vm_manager_diagram', 'af.shizuku.manager.settings.VMManagerDiagramPreference');
insertAfter(pPlus, 'continuity_bridge_enabled', 'continuity_bridge_diagram', 'af.shizuku.manager.settings.ContinuityBridgeDiagramPreference');
insertAfter(pPlus, 'overlay_manager_plus_enabled', 'overlay_manager_plus_diagram', 'af.shizuku.manager.settings.ThemingBridgeDiagramPreference');
insertAfter(pPlus, 'ai_core_plus_enabled', 'ai_core_plus_diagram', 'af.shizuku.manager.settings.AICoreBridgeDiagramPreference');

