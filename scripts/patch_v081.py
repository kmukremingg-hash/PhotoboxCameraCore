from pathlib import Path

main = Path('app/src/main/java/com/kevo/photoboxcamera/MainActivity.kt')
text = main.read_text(encoding='utf-8')

old = '''    private fun localIpv4(): String? = try {\n        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress\n    } catch (_:Throwable){ null }'''

new = '''    private fun localIpv4(): String? = try {\n        // 0.8.1: ausdrücklich die WLAN-Adresse verwenden.\n        // Die alte Version nahm einfach die erste IPv4-Adresse; auf Samsung konnte das\n        // Mobilfunk oder VPN sein und war vom Tablet aus nicht erreichbar.\n        val cm = getSystemService(android.net.ConnectivityManager::class.java)\n        val wifiNetwork = cm.allNetworks.firstOrNull { network ->\n            cm.getNetworkCapabilities(network)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true\n        }\n        val wifiIp = wifiNetwork?.let { network ->\n            cm.getLinkProperties(network)?.linkAddresses\n                ?.map { it.address }\n                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }\n                ?.hostAddress\n        }\n        wifiIp ?: NetworkInterface.getNetworkInterfaces().toList()\n            .filter { it.isUp && !it.isLoopback && (it.name.startsWith(\"wlan\") || it.name.startsWith(\"ap\")) }\n            .flatMap { it.inetAddresses.toList() }\n            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }\n            ?.hostAddress\n    } catch (_:Throwable){ null }'''

if old not in text:
    raise SystemExit('localIpv4 block not found; refusing unsafe patch')
text = text.replace(old, new)
text = text.replace('Photobox 0.8 STARTFEHLER', 'Photobox 0.8.1 STARTFEHLER')
text = text.replace('Collectooow Photobox 0.8', 'Collectooow Photobox 0.8.1')
main.write_text(text, encoding='utf-8')

manifest = Path('app/src/main/AndroidManifest.xml')
m = manifest.read_text(encoding='utf-8')
permission = '    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />\n'
if 'android.permission.ACCESS_NETWORK_STATE' not in m:
    m = m.replace('    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />\n', '    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />\n' + permission)
manifest.write_text(m, encoding='utf-8')

layout = Path('app/src/main/res/layout/activity_main.xml')
l = layout.read_text(encoding='utf-8').replace('Collectooow! Photobox Camera 0.8', 'Collectooow! Photobox Camera 0.8.1')
layout.write_text(l, encoding='utf-8')

build = Path('app/build.gradle.kts')
b = build.read_text(encoding='utf-8')
b = b.replace('versionCode = 8', 'versionCode = 9')
b = b.replace('versionName = "0.8"', 'versionName = "0.8.1"')
build.write_text(b, encoding='utf-8')

print('Photobox Camera 0.8.1 WLAN-IP patch applied')
