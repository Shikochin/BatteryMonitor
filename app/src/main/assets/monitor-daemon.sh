#!/system/bin/sh
# Battery Monitor sampling daemon.
#
# Started through Shizuku (uid 0) and detached from the app process, so it keeps
# sampling while the UI is backgrounded — Android freezes the app's own process
# (cgroup.freeze=1) about ten seconds after it leaves the foreground.
#
# One line per sample, pipe separated:
#   epochSeconds|screenOn|level|voltage_uV|current_raw|temp_tenths|status|charge_counter_uAh|foregroundPackage
# Missing values are left empty. The rated capacity (`charge_full`) is *not* repeated on every
# line — it is written once per iteration to the small, overwritten STATE file instead.
# The foreground package comes from the same `topResumedActivity` line Scene's own daemon uses,
# so the app can attribute a sample without waiting for a `dumpsys batterystats --history` dump.
#
# Exactly one sampler may run at a time. A hard link is used as an atomic lock:
# `ln` fails when the link already exists, so only the first process wins. Every
# other sampler (an older build, a manual start) is reaped before the loop starts.
#
# The daemon also publishes a heartbeat (`pid|epoch|screenOn|interval`) every
# iteration, which is what lets the app tell "alive and sampling" apart from
# "process still exists but is wedged".
#
# Usage:
#   sh bm-daemon.sh --run  LOG PIDFILE [STATE] [LOCK]
#   sh bm-daemon.sh --stop [PIDFILE] [STATE] [LOCK]

SELF="$0"
case "$SELF" in
  /*) ;;
  *) SELF="/data/local/tmp/bm-daemon.sh" ;;
esac

# Bump whenever this file changes: the app compares it with the version published in STATE and
# replaces a daemon left over from an older build (otherwise an app update would keep sampling
# with the old script until the next reboot).
VERSION=3

# True when $1 is a live sampler. `sh -c` wrappers are excluded because their
# command text contains this script's name and they must never be killed.
is_sampler() {
  pid="$1"
  [ -n "$pid" ] || return 1
  kill -0 "$pid" 2>/dev/null || return 1
  cmd=$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null)
  case "$cmd" in
    "sh -c "*|"/system/bin/sh -c "*) return 1 ;;
    *bm-daemon.sh*) return 0 ;;
  esac
  return 1
}

# `--stop`: kill every sampler and remove the pid/state/lock files.
if [ "$1" = "--stop" ]; then
  PIDFILE="$2"
  STATE="$3"
  LOCK="${4:-/data/local/tmp/bm-daemon.lock}"
  for entry in /proc/[0-9]*; do
    pid=${entry#/proc/}
    [ "$pid" = "$$" ] && continue
    is_sampler "$pid" && kill -9 "$pid" 2>/dev/null
  done
  [ -n "$PIDFILE" ] && rm -f "$PIDFILE" 2>/dev/null
  [ -n "$STATE" ] && rm -f "$STATE" 2>/dev/null
  rm -f "$LOCK" 2>/dev/null
  exit 0
fi

[ "$1" = "--run" ] && shift
LOG="$1"
PIDFILE="$2"
STATE="${3:-$PIDFILE.state}"
LOCK="${4:-/data/local/tmp/bm-daemon.lock}"
[ -n "$LOG" ] && [ -n "$PIDFILE" ] || exit 1

DIR=$(dirname "$LOG")
[ -d "$DIR" ] || mkdir -p "$DIR" 2>/dev/null
[ -d "$DIR" ] || exit 1
: >> "$LOG" 2>/dev/null
chmod 666 "$LOG" 2>/dev/null

# Atomic single-instance lock. Returns 1 when another live sampler owns it.
acquire_lock() {
  tries=0
  while [ "$tries" -lt 30 ]; do
    printf '%s\n' "$$" > "$LOCK.$$" 2>/dev/null
    if ln "$LOCK.$$" "$LOCK" 2>/dev/null; then
      rm -f "$LOCK.$$" 2>/dev/null
      return 0
    fi
    rm -f "$LOCK.$$" 2>/dev/null

    owner=$(cat "$LOCK" 2>/dev/null)
    if [ -z "$owner" ]; then
      # The winner may not have written its pid yet.
      sleep 0.1
      owner=$(cat "$LOCK" 2>/dev/null)
    fi
    if [ -n "$owner" ] && is_sampler "$owner"; then
      return 1
    fi
    # Stale lock: delete it only while it still holds the dead owner's pid.
    current=$(cat "$LOCK" 2>/dev/null)
    [ "$current" = "$owner" ] && rm -f "$LOCK" 2>/dev/null
    tries=$((tries + 1))
    sleep 0.1
  done
  return 1
}

# Kill samplers left over by an older build / a manual start. Only the lock owner
# gets here, so this cannot fight with another new daemon. `ps -o ARGS` is used
# instead of a per-pid /proc sweep: forking `tr` for every process takes seconds.
reap_others() {
  ps -A -o PID,ARGS 2>/dev/null > "$LOCK.$$.ps"
  while read -r pid args; do
    [ "$pid" = "$$" ] && continue
    case "$args" in
      "sh -c "*|"/system/bin/sh -c "*) continue ;;
      *bm-daemon.sh*) kill -9 "$pid" 2>/dev/null ;;
    esac
  done < "$LOCK.$$.ps"
  rm -f "$LOCK.$$.ps" 2>/dev/null
}

if ! acquire_lock; then
  # Another sampler is already alive and owns the lock: nothing to do.
  exit 0
fi

screen=0
interval=15
MAX_BYTES=4194304
ROTATE_EVERY=40

# `pid|epochSeconds|screenOn|interval|chargeFull_uAh|scriptVersion` — read directly by the app.
# Rewritten in place every iteration, so it never grows; `chargeFull` is constant for a session
# and does not belong in the sample log.
write_state() {
  printf '%s|%s|%s|%s|%s|%s\n' "$$" "${1:-$(date +%s)}" "$screen" "$interval" "$cf" "$VERSION" > "$STATE.$$" 2>/dev/null
  mv -f "$STATE.$$" "$STATE" 2>/dev/null
  chmod 666 "$STATE" 2>/dev/null
}

cleanup() {
  [ "$(cat "$LOCK" 2>/dev/null)" = "$$" ] && rm -f "$LOCK" 2>/dev/null
  [ "$(cat "$PIDFILE" 2>/dev/null)" = "$$" ] && rm -f "$PIDFILE" "$STATE" 2>/dev/null
  rm -f "$LOCK.$$" "$LOCK.$$.ps" "$PIDFILE.$$" "$STATE.$$" 2>/dev/null
}
trap cleanup EXIT
trap 'exit 0' INT TERM HUP

# Reap first, publish second: when the pidfile appears the new daemon is already
# the only sampler, so a probe can never observe a transient two-instance state.
reap_others

printf '%s\n' "$$" > "$PIDFILE.$$" 2>/dev/null
mv -f "$PIDFILE.$$" "$PIDFILE" 2>/dev/null
chmod 666 "$PIDFILE" 2>/dev/null
write_state

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

  echo "$now|$screen|$cap|$volt|$cur|$temp|$status|$cc|$fg" >> "$LOG"

  # Cheap cadence: 3 s only while the screen is on and the pack is discharging.
  if [ "$screen" = "1" ] && { [ "$status" = "3" ] || [ "$status" = "4" ]; }; then
    interval=3
  else
    interval=15
  fi

  write_state "$now"
  # Keep the sampling cadence independent of the time spent reading sysfs and running dumpsys.
  # Sleeping a full interval after collection makes every sample late by the command duration,
  # which accumulates into a visible drift on the chart.
  finished=$(date +%s)
  sleep_for=$((interval - finished + now))
  [ "$sleep_for" -gt 0 ] && sleep "$sleep_for"

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
