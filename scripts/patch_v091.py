from pathlib import Path

# Wird NACH patch_v081.py und patch_v090.py ausgefuehrt.

main = Path('app/src/main/java/com/kevo/photoboxcamera/MainActivity.kt')
text = main.read_text(encoding='utf-8')
text = text.replace('Photobox 0.9 STARTFEHLER', 'Photobox 0.9.1 STARTFEHLER')
text = text.replace('Collectooow Fotobox 0.9', 'Collectooow Fotobox 0.9.1')
main.write_text(text, encoding='utf-8')

layout = Path('app/src/main/res/layout/activity_main.xml')
l = layout.read_text(encoding='utf-8')
l = l.replace('Collectooow! Fotobox 0.9', 'Collectooow! Fotobox 0.9.1')
l = l.replace('0.9-Regel:', '0.9.1-Regel:')
layout.write_text(l, encoding='utf-8')

build = Path('app/build.gradle.kts')
b = build.read_text(encoding='utf-8')
b = b.replace('versionCode = 10', 'versionCode = 11')
b = b.replace('versionName = "0.9"', 'versionName = "0.9.1"')
build.write_text(b, encoding='utf-8')

print('Collectooow Fotobox 0.9.1 version patch applied')
