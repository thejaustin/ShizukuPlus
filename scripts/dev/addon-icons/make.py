#!/usr/bin/env python3
# Regenerates the Compat Hub (link) and Dhizuku Compat (shield) icons: launcher foreground,
# themed layer, legacy PNGs and the Hub's permission glyph, all from geo.py. Needs rsvg-convert.
# Run: python3 scripts/dev/addon-icons/make.py
import sys, os, shutil, subprocess, base64
sys.path.insert(0, os.path.dirname(__file__)); from geo import *
DENS={'hdpi':72,'xhdpi':96,'xxhdpi':144,'xxxhdpi':192}
MRES=REPO+'/manager/src/main/res'
S24=0.30
NOTE={'link':'a chain link: this app links stock-Shizuku apps to Shizuku+',
      'shield':'a shield: this app hands device-owner (Dhizuku) access to Shizuku+'}

def badge_vd(kind, cx, cy, s, color, indent='    '):
    if kind=='link':
        paths,sw=link(cx,cy,s)
        # One path for both links: stroked as a single shape, so the 70% white doesn't double up
        # where the links overlap.
        inner=f'\n{indent}    <path\n{indent}        android:fillColor="#00000000"\n{indent}        android:strokeColor="{color}"\n{indent}        android:strokeWidth="{sw:.3f}"\n{indent}        android:pathData="{"".join(paths)}" />'
        return f'{indent}<group\n{indent}    android:pivotX="{cx}"\n{indent}    android:pivotY="{cy}"\n{indent}    android:rotation="-45">{inner}\n{indent}</group>'
    return f'{indent}<path\n{indent}    android:fillColor="{color}"\n{indent}    android:pathData="{shield(cx,cy,s)}" />'

def gap_for(kind): return 3.5 if kind=='link' else 3.2

def glyph_vd(kind, name):
    return f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <!-- {name}'s glyph in Android's permission screens: the stock cat-and-hexagon silhouette
         (manager's ic_permission_shizuku) with {NOTE[kind]}, in the spot
         where Shizuku+ shows its "+". White fill: Settings tints it. Literal colours, because
         below API 24 the build turns this into PNGs and that step can't resolve colour references. Generated with the
         launcher badge from the same geometry; keep the two in step. -->
    <path
        android:fillColor="#FFFFFFFF"
        android:fillType="evenOdd"
        android:pathData="{glyph_paths(gap_for(kind))}" />
{badge_vd(kind, CX, CY, S24, '#FFFFFFFF')}
</vector>
'''

def foreground_vd(kind, name):
    return f'''<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- Upstream Shizuku's cat-and-hexagon art (copied from the manager) with {name}'s badge,
         {NOTE[kind]}. It sits where the Shizuku+ icon has its "+": centred on
         the hexagon's top-right vertex (64.06,36.2) in 108-unit space, inside the safe zone, in the
         same 70% white. -->
    <item android:drawable="@mipmap/ic_launcher_foreground_base" />
    <item>
        <vector
            android:width="108dp"
            android:height="108dp"
            android:viewportWidth="108"
            android:viewportHeight="108">
{badge_vd(kind, 64.06, 36.2, 1.0, '#B3FFFFFF', '            ')}
        </vector>
    </item>
</layer-list>
'''

def mono_vd(kind, name):
    return f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="432"
    android:viewportHeight="432"
    android:tint="?android:attr/colorControlNormal">
    <!-- Themed-icon layer: the 24-unit permission glyph scaled x8 and centred, so every point stays
         within radius 110 of the 432-unit canvas (inside the 66dp safe circle). -->
    <group
        android:scaleX="8"
        android:scaleY="8"
        android:translateX="120"
        android:translateY="120">
        <path
            android:fillColor="#FFFFFFFF"
            android:fillType="evenOdd"
            android:pathData="{glyph_paths(gap_for(kind))}" />
{badge_vd(kind, CX, CY, S24, '#FFFFFFFF', '        ')}
    </group>
</vector>
'''

ADAPTIVE='''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
'''

def legacy_png(kind, out, px):
    """Pre-API-26 launcher icon: the adaptive layers composited and masked to a circle."""
    b64=lambda p: base64.b64encode(open(p,'rb').read()).decode()
    BG=b64(MRES+'/mipmap-xxxhdpi/ic_launcher_background.png'); FG=b64(MRES+'/mipmap-xxxhdpi/ic_launcher_foreground_base.png')
    if kind=='link':
        paths,sw=link(64.06,36.2,1.0)
        badge=f'<g transform="rotate(-45 64.06 36.2)" fill="none" stroke="#fff" stroke-opacity="0.70" stroke-width="{sw}">'+f'<path d="{"".join(paths)}"/></g>'
    else:
        badge=f'<path d="{shield(64.06,36.2,1.0)}" fill="#fff" fill-opacity="0.70"/>'
    svg=(f'<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="18 18 72 72" width="{px}" height="{px}">'
         f'<clipPath id="c"><circle cx="54" cy="54" r="34"/></clipPath><g clip-path="url(#c)">'
         f'<image width="108" height="108" xlink:href="data:image/png;base64,{BG}"/>'
         f'<image width="108" height="108" xlink:href="data:image/png;base64,{FG}"/>{badge}</g></svg>')
    tmp=out+'.svg'; open(tmp,'w').write(svg)
    subprocess.run(['rsvg-convert',tmp,'-o',out],check=True); os.remove(tmp)

def emit(module, kind, name, perm_name, legacy):
    res=f'{REPO}/{module}/src/main/res'
    # Only the API 26+ adaptive icon uses these; on a module whose minSdk is below 24 they go in
    # drawable-v26 so the build doesn't try to turn them into PNGs (it can't with ?attr tints).
    adir=res+('/drawable-v26' if legacy else '/drawable')
    for d in (res+'/drawable',adir,res+'/mipmap-anydpi-v26'): os.makedirs(d,exist_ok=True)
    open(adir+'/ic_launcher_foreground.xml','w').write(foreground_vd(kind,name))
    open(adir+'/ic_launcher_monochrome.xml','w').write(mono_vd(kind,name))
    open(res+'/mipmap-anydpi-v26/ic_launcher.xml','w').write(ADAPTIVE)
    if perm_name: open(res+f'/drawable/{perm_name}.xml','w').write(glyph_vd(kind,name))
    for d,px in DENS.items():
        os.makedirs(f'{res}/mipmap-{d}',exist_ok=True)
        for f in ['ic_launcher_background.png','ic_launcher_foreground_base.png']:
            shutil.copy(f'{MRES}/mipmap-{d}/{f}',f'{res}/mipmap-{d}/{f}')
        if legacy: legacy_png(kind,f'{res}/mipmap-{d}/ic_launcher.png',px)

emit('compat','link','The Compat Hub','ic_permission_hub',legacy=True)
emit('dhizuku-compat','shield','Dhizuku Compat',None,legacy=False)
print('done')
