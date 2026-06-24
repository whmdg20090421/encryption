#!/usr/bin/env python3
"""将 Android vector drawable XML 转换为 SVG，方便预览。"""
import sys
import re

def convert(xml_path, svg_path=None):
    with open(xml_path, 'r') as f:
        xml = f.read()

    # 提取属性值（忽略命名空间前缀）
    def attr(tag, name, default=''):
        # 匹配 android:name="value" 或 name="value"
        m = re.search(rf'(?:android:){name}="([^"]*)"', tag)
        return m.group(1) if m else default

    # 解析 vector 根元素
    root_m = re.search(r'<vector\b([^>]*)>', xml)
    root_attrs = root_m.group(1) if root_m else ''
    vw = float(attr(root_attrs, 'viewportWidth', '24'))
    vh = float(attr(root_attrs, 'viewportHeight', '24'))
    w = attr(root_attrs, 'width', '24dp').replace('dp', '')
    h = attr(root_attrs, 'height', '24dp').replace('dp', '')
    scale = 10

    svg_w = float(w) * scale
    svg_h = float(h) * scale

    # 提取所有 <path .../>
    paths_svg = []
    for m in re.finditer(r'<path\b([^>]*)/?>', xml):
        tag = m.group(1)
        d = attr(tag, 'pathData')
        if not d:
            continue
        fill = attr(tag, 'fillColor', '#000000')
        stroke = attr(tag, 'strokeColor')
        stroke_w = attr(tag, 'strokeWidth')

        if fill == '#00000000':
            fill = 'none'

        attrs = f'd="{d}" fill="{fill}"'
        if stroke and stroke != '#00000000':
            attrs += f' stroke="{stroke}"'
        if stroke_w:
            attrs += f' stroke-width="{stroke_w}"'
        paths_svg.append(f'  <path {attrs}/>')

    svg = f'''<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg"
     width="{svg_w}" height="{svg_h}"
     viewBox="0 0 {vw} {vh}">
  <rect width="{vw}" height="{vh}" fill="#f5f5f5" rx="2"/>
{chr(10).join(paths_svg)}
</svg>'''

    if svg_path is None:
        svg_path = xml_path.replace('.xml', '.svg')
    with open(svg_path, 'w') as f:
        f.write(svg)
    print(f'已生成: {svg_path}')

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print('用法: python3 vector_to_svg.py <input.xml> [output.svg]')
        sys.exit(1)
    convert(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
