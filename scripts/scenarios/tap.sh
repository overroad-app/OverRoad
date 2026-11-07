# 사용: tap.sh <기기> <버튼 문구 일부> [last]  — 앱 버튼을 문구로 찾아 탭한다 (같은 문구가 여러 개면 첫 번째, last면 마지막)
ADB=~/Library/Android/sdk/platform-tools/adb
$ADB -s $1 shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
b=$($ADB -s $1 exec-out cat /sdcard/ui.xml | grep -oE "text=\"[^\"]*$2\"[^>]*bounds=\"[^\"]+\"" | { [ "$3" = last ] && tail -1 || head -1; } | grep -oE 'bounds="[^"]+"' | grep -oE '[0-9]+' | tr '\n' ' ')
read x1 y1 x2 y2 <<< "$b"
$ADB -s $1 shell input tap $(( (x1+x2)/2 )) $(( (y1+y2)/2 ))
