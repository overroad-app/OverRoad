# 사용: tap.sh <기기> <버튼 문구 일부>  — 앱 버튼을 문구로 찾아 탭한다
ADB=~/Library/Android/sdk/platform-tools/adb
$ADB -s $1 shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
b=$($ADB -s $1 exec-out cat /sdcard/ui.xml | grep -oE "text=\"[^\"]*$2\"[^>]*bounds=\"[^\"]+\"" | grep -oE 'bounds="[^"]+"' | grep -oE '[0-9]+' | tr '\n' ' ')
read x1 y1 x2 y2 <<< "$b"
$ADB -s $1 shell input tap $(( (x1+x2)/2 )) $(( (y1+y2)/2 ))
