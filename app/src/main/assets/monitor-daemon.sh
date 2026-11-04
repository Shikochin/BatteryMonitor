#!/system/bin/sh
# Battery Monitor sampling daemon.
#
# Started through Shizuku (uid 0) and detached from the app process, so it keeps
# sampling while the UI is backgrounded — Android freezes the app's own process
# (cgroup.freeze=1) about ten seconds after it leaves the foreground.
#
# One line per sample, pipe separated:
#   epochSeconds|screenOn|level|voltage_uV|current_raw|temp_tenths|status|charge_counter_uAh|charge_full_uAh|foregroundPackage
# Missing values are left empty. The foreground package comes from the same
# `topResumedActivity` line Scene's own daemon uses, so the app can attribute a
# sample without waiting for a `dumpsys batterystats --history` dump.

LOG="$1"
PIDFILE="$2"
MAX_BYTES=4194304
ROTATE_EVERY=40

DIR="$(dirname "$LOG")"
[ -d "$DIR" ] || mkdir -p "$DIR" 2>/dev/null
: >> "$LOG" 2>/dev/null
chmod 666 "$LOG" 2>/dev/null
echo "$$" > "$PIDFILE"

count=0
while :; do
  # The app was uninstalled or its data wiped: stop quietly.
  [ -d "$DIR" ] || exit 0

  now=$(date +%s)

  cur=""
  IFS= read -r cur < /sys/class/power_supply/battery/current_now 2>/dev/null
  volt=""
  IFS= read -r volt < /sys/class/power_supply/battery/voltage_now 2>/dev/null
  cap=""
  IFS= read -r cap < /sys/class/power_supply/battery/capacity 2>/dev/null
  temp=""
  IFS= read -r temp < /sys/class/power_supply/battery/temp 2>/dev/null
  status_raw=""
  IFS= read -r status_raw < /sys/class/power_supply/battery/status 2>/dev/null
  cc=""
  IFS= read -r cc < /sys/class/power_supply/battery/charge_counter 2>/dev/null
  [ -n "$cc" ] || IFS= read -r cc < /sys/class/power_supply/bms/charge_counter 2>/dev/null
  cf=""
  IFS= read -r cf < /sys/class/power_supply/battery/charge_full 2>/dev/null
  [ -n "$cf" ] || IFS= read -r cf < /sys/class/power_supply/bms/charge_full 2>/dev/null

  case "$status_raw" in
    Charging) status=2 ;;
    Discharging) status=3 ;;
    "Not charging") status=4 ;;
    Full) status=5 ;;
    *) status=0 ;;
  esac

  wake=$(dumpsys power 2>/dev/null | grep -m1 -o 'mWakefulness=[A-Za-z]*')
  if [ "$wake" = "mWakefulness=Awake" ]; then
    screen=1
  else
    screen=0
  fi

  fg=""
  if [ "$screen" = "1" ]; then
    record=$(dumpsys activity activities 2>/dev/null | grep -m1 -oE '(topResumedActivity|mResumedActivity)=ActivityRecord\{[^}]*\}')
    fg=$(printf '%s' "$record" | sed 's/.* u[0-9][0-9]* \([^/]*\)\/.*/\1/')
    case "$fg" in
      ""|*[!A-Za-z0-9._]*) fg="" ;;
    esac
  fi

  echo "$now|$screen|$cap|$volt|$cur|$temp|$status|$cc|$cf|$fg" >> "$LOG"

  # Cheap cadence: 3 s only while the screen is on and the pack is discharging.
  if [ "$screen" = "1" ] && { [ "$status" = "3" ] || [ "$status" = "4" ]; }; then
    interval=3
  else
    interval=15
  fi
  sleep "$interval"

  count=$((count + 1))
  if [ "$count" -ge "$ROTATE_EVERY" ]; then
    count=0
    size=$(wc -c < "$LOG" 2>/dev/null || echo 0)
    if [ "${size:-0}" -gt "$MAX_BYTES" ]; then
      tail -n 3000 "$LOG" > "$LOG.tmp" 2>/dev/null && mv "$LOG.tmp" "$LOG"
      chmod 666 "$LOG" 2>/dev/null
    fi
  fi
done
