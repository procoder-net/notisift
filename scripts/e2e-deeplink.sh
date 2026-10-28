#!/usr/bin/env bash
# End-to-end check that tapping a NotiSift "Important" row opens the exact chat the original pointed to.
# Needs: an unlocked phone on adb, NotiSift (debug) installed with notification access, and the test sender:
#   ./gradlew :testsender:installDebug && adb shell pm grant io.github.notisift.testsender android.permission.POST_NOTIFICATIONS
set -euo pipefail
CHAT="${1:-E2E Chat $RANDOM}"
SENDER=io.github.notisift.testsender

adb logcat -c
adb shell am start -n "$SENDER/.PostActivity" --es chat "'$CHAT'" --es text "'Deep link check'" >/dev/null
sleep 5

# 1. NotiSift replaced the original with its own row, and that row still carries the sender's tap action.
row=$(adb shell dumpsys notification --noredact | grep -A60 "pkg=io.github.notisift .*tag=m:0|$SENDER|1|$CHAT|" || true)
[[ -n "$row" ]] || { echo "FAIL: no NotiSift row for '$CHAT'"; exit 1; }
echo "$row" | grep -q "contentIntent=.*PendingIntentRecord{[^ ]* $SENDER startActivity" \
  || { echo "FAIL: row's tap action is not the sender's own"; exit 1; }
echo "ok: row mirrors the original tap action"

# 2. Tap the row in the drawer and confirm the sender's chat screen opened for this exact chat. The drawer
#    re-sorts for a moment after the row arrives, so find the row again before each of up to 3 taps.
find_row() {
  adb shell uiautomator dump /sdcard/sift-ui.xml >/dev/null
  adb exec-out cat /sdcard/sift-ui.xml | python3 -c "
import re,sys
s=sys.stdin.read()
m=re.search(r'text=\"' + re.escape(sys.argv[1]) + r'\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', s)
# Tap the row body (right of the title, slightly below it), not the title text itself.
print('' if not m else f'{(int(m[1])+int(m[3]))//2 + 250} {(int(m[2])+int(m[4]))//2 + 25}')" "$CHAT"
}
for attempt in 1 2 3; do
  adb shell input keyevent KEYCODE_HOME
  adb shell cmd statusbar expand-notifications
  sleep 4   # let the drawer finish animating, or the tap lands mid-slide
  bounds=$(find_row)
  if [[ -z "$bounds" ]]; then
    # Samsung collapses each stack to one row; open the stacks one by one until the row shows up.
    for expand in $(adb exec-out cat /sdcard/sift-ui.xml | python3 -c "
import re,sys
for m in re.finditer(r'resource-id=\"android:id/(?:expand_button|group_child_count_number)\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', sys.stdin.read()):
    print(f'{(int(m[1])+int(m[3]))//2},{(int(m[2])+int(m[4]))//2}')"); do
      adb shell input tap ${expand/,/ }
      sleep 2
      # Rows shift while stacks open; a tap that lands on our row and opens it counts too.
      if adb logcat -d -s SiftTest:I | grep -q "OPENED chat=$CHAT"; then
        echo "PASS: tapping the row opened chat '$CHAT' (while opening its stack)"
        exit 0
      fi
      bounds=$(find_row)
      [[ -n "$bounds" ]] && break
    done
  fi
  [[ -n "$bounds" ]] || { echo "FAIL: row not visible in the drawer (is the phone unlocked?)"; exit 1; }
  adb shell input tap $bounds
  sleep 3
  if adb logcat -d -s SiftTest:I | grep -q "OPENED chat=$CHAT"; then
    echo "PASS: tapping the row opened chat '$CHAT' (tap $attempt)"
    exit 0
  fi
done
echo "FAIL: chat '$CHAT' did not open"
exit 1
