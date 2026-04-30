A small test app that allows you to see what other apps on your device can detect about your network settings.

Functionality:

1. Shows interfaces and their IP addresses
2. Highlights whether a TUN, PPP, or WG interface exists in the system. Detects them in two different ways. Some apps, such as XPL-EX (XLuaPrivacy) [https://github.com/0bbedCode/XPL-EX](https://github.com/0bbedCode/XPL-EX), can spoof certain parameters but not all of them, so this app helps you verify whether spoofing is working.
3. Displays transport types and capabilities
4. Shows which installed apps have VPN-related services
5. Displays DNS servers
6. Checks external public IP addresses (via direct connection and via TUN/PPP/WG interfaces, if the system allows it — only applicable to older Android versions)
7. Attempts to detect default local SOCKS ports commonly opened by popular VPN apps and tries to retrieve the IP address through a SOCKS proxy
8. Checks whether popular VPN apps are installed on the device
9. Shows a full list of transport and network (deprecated) capabilities

Provides a brief overview of potential privacy issues for VPN users.

