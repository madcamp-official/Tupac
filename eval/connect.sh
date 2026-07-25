#!/bin/sh
# 폰과 무선으로 연결하고 MCP 포트를 포워딩한다.
#
#   sh eval/connect.sh
#
# 이미 무선으로 붙어 있으면 그대로 쓰고, USB만 꽂혀 있으면 무선으로 전환한다.
# 처음 한 번은 USB 케이블이 필요하다(adb tcpip를 걸어야 하므로). 그 뒤로는
# 폰을 재부팅하기 전까지 케이블 없이 된다.
#
# 이 스크립트는 토큰을 다루지 않는다. 연결이 끝나면 아래를 실행하고 agent를 돌린다.
#   export TOKEN=$(adb shell run-as com.example.mobileguiagent \
#       cat /data/data/com.example.mobileguiagent/shared_prefs/pocket_mcp_auth.xml \
#       | sed -n 's/.*name="bearer_token">\([^<]*\)<.*/\1/p')
set -e

ADB_PORT=5555
MCP_PORT=${POCKETMCP_PORT:-9911}
DEVICE_PORT=8765

wireless=$(adb devices | awk -v p=":$ADB_PORT" 'index($1, p) && $2 == "device" {print $1; exit}')

if [ -z "$wireless" ]; then
    # 무선 연결이 없으니 USB로 붙은 기기를 찾아 전환한다.
    usb=$(adb devices | awk '$2 == "device" && $1 !~ /:/ {print $1; exit}')
    if [ -z "$usb" ]; then
        echo "연결된 기기가 없습니다. USB 케이블을 꽂고 다시 실행하세요." >&2
        echo "  (폰에 'USB 디버깅 허용' 팝업이 뜨면 허용해야 합니다)" >&2
        exit 1
    fi

    # 이 Wi-Fi가 IPv6 전용이면 wlan0에 IPv4 주소가 아예 없다(192.0.0.x는
    # 464XLAT 내부 주소라 기기 간 통신에 못 쓴다). IPv6를 먼저 보고, 없으면
    # IPv4로 물러난다. temporary 주소는 주기적으로 바뀌므로 mngtmpaddr(EUI-64
    # 기반 안정 주소)을 고른다.
    address=$(adb -s "$usb" shell ip -f inet6 addr show wlan0 2>/dev/null \
        | awk '/mngtmpaddr/ {split($2, a, "/"); print a[1]; exit}')
    if [ -n "$address" ]; then
        address="[$address]"
    else
        address=$(adb -s "$usb" shell ip -f inet addr show wlan0 2>/dev/null \
            | awk '/inet /{split($2, a, "/"); print a[1]; exit}')
    fi
    if [ -z "$address" ]; then
        echo "폰의 Wi-Fi 주소를 찾지 못했습니다. 폰이 Wi-Fi에 연결돼 있는지 확인하세요." >&2
        exit 1
    fi

    echo "무선 모드로 전환합니다 ($address)"
    adb -s "$usb" tcpip "$ADB_PORT" >/dev/null
    sleep 4
    adb connect "$address:$ADB_PORT" >/dev/null
    sleep 2
    wireless=$(adb devices | awk -v p=":$ADB_PORT" 'index($1, p) && $2 == "device" {print $1; exit}')
    if [ -z "$wireless" ]; then
        echo "무선 연결에 실패했습니다. 맥과 폰이 같은 Wi-Fi에 있는지, VPN이 켜져" >&2
        echo "있지 않은지 확인하세요(VPN은 기기 간 통신을 막는 경우가 많습니다)." >&2
        exit 1
    fi
fi

adb -s "$wireless" forward "tcp:$MCP_PORT" "tcp:$DEVICE_PORT" >/dev/null
echo "연결됨: $wireless  (127.0.0.1:$MCP_PORT → 폰 $DEVICE_PORT)"
echo "이제 케이블을 뽑아도 됩니다."
