import re, math
import os
REPO=os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..'))
CX,CY=19.42,4.12          # hexagon top-right vertex in the 24-unit glyph
def glyph_paths(gap):
    """The stock cat+hexagon glyph with a circular gap of radius `gap` at the vertex."""
    s=open(REPO+'/manager/src/main/res/drawable/ic_permission_shizuku.xml').read()
    p=re.search(r'pathData="([^"]+)"',s).group(1)
    a=(round(CX-gap,2),CY); b=(round(CX+gap*0.5,2),round(CY+gap*math.sqrt(3)/2,2))
    old='L13.24,4.12L19.42,4.12L22.5,9.45Z'
    assert old in p
    return p.replace(old,f'L13.24,4.12L{a[0]},{a[1]}A{gap},{gap} 0,0 0 {b[0]},{b[1]}L22.5,9.45Z')
def capsule(cx,cy,w,h):
    r=h/2; x0=cx-w/2+r; x1=cx+w/2-r
    f=lambda v: f'{v:.3f}'.rstrip('0').rstrip('.')
    return (f'M{f(x0)},{f(cy-r)}H{f(x1)}A{f(r)},{f(r)} 0,0 1 {f(x1)},{f(cy+r)}'
            f'H{f(x0)}A{f(r)},{f(r)} 0,0 1 {f(x0)},{f(cy-r)}Z')
def link(cx,cy,s):
    """Two chain links, unrotated (caller rotates -45). s = scale vs the 108-unit design."""
    w,h=8.6*s,5.8*s
    return [capsule(cx-3.3*s,cy,w,h),capsule(cx+3.3*s,cy,w,h)], 2.6*s
def shield(cx,cy,s):
    f=lambda v: f'{v:.3f}'.rstrip('0').rstrip('.')
    X=lambda d: f(cx+d*s); Y=lambda d: f(cy+d*s)
    return (f'M{X(0)},{Y(-7.4)}L{X(6.2)},{Y(-4.8)}V{Y(-0.4)}C{X(6.2)},{Y(3.8)} {X(3.4)},{Y(6.6)} {X(0)},{Y(7.6)}'
            f'C{X(-3.4)},{Y(6.6)} {X(-6.2)},{Y(3.8)} {X(-6.2)},{Y(-0.4)}V{Y(-4.8)}Z')
