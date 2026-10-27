#!/usr/bin/env bash
# Run a local Jira Software 9.4 for Sprint Pulse development, with the test data loaded.
#
#   scripts/dev-jira.sh start    start Jira (if needed) and load the test data (if missing)
#   scripts/dev-jira.sh stop     stop Jira cleanly
#   scripts/dev-jira.sh status   show whether Jira is running
#   scripts/dev-jira.sh seed     load the test data into a running Jira (if missing)
#   scripts/dev-jira.sh reset    delete and reload the SP and MOB test projects
#   scripts/dev-jira.sh fresh    set the current Jira aside and start a brand-new one
#   scripts/dev-jira.sh logs     follow the Jira log
#
# Jira runs at http://localhost:2990/jira (admin / admin). Its data lives in
# my-jira-gadget/target/jira; this script never deletes it (`fresh` moves it to a backup).
# If it is gone (for example after `mvn clean`), `start` creates a new Jira and loads the
# test data again.
#
# Works on Linux and on WSL 2. Environment overrides: JAVA_HOME (a JDK 11), START_TIMEOUT
# (seconds, default 1800), MAVEN_ARGS (extra Maven options, Maven 3.9+).
set -euo pipefail

# -P resolves symlinks, so a link from the Linux home into /mnt/c is caught by preflight.
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
PLUGIN_DIR=$ROOT/my-jira-gadget
JIRA_URL=http://localhost:2990/jira
STATE_DIR=${XDG_STATE_HOME:-$HOME/.local/state}/sprint-pulse
BACKUP_DIR=$STATE_DIR/backups
LOG=$STATE_DIR/jira.log
PID_FILE=$STATE_DIR/jira.pid
START_TIMEOUT=${START_TIMEOUT:-1800}

die() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }
note() { printf '%s\n' "$*"; }

is_wsl() { grep -qi microsoft /proc/version 2>/dev/null; }

# True when the path is a Windows drive mounted into WSL (C:\ appears as /mnt/c over 9P).
on_windows_fs() {
    local fstype
    case "$1" in /mnt/[a-zA-Z]|/mnt/[a-zA-Z]/*) is_wsl && return 0 ;; esac
    fstype=$(findmnt -n -o FSTYPE -T "$1" 2>/dev/null || stat -f -c %T "$1" 2>/dev/null || true)
    case "$fstype" in 9p|v9fs|drvfs) return 0 ;; esac
    return 1
}

# --- checks -------------------------------------------------------------------------------

find_java11() {
    local candidates=() c
    [ -n "${JAVA_HOME:-}" ] && candidates+=("$JAVA_HOME")
    for c in "$HOME"/tools/jdk-11* /usr/lib/jvm/*-11-* /usr/lib/jvm/*11* /usr/lib/jvm/temurin-11*; do
        [ -d "$c" ] && candidates+=("$c")
    done
    if command -v java >/dev/null; then
        candidates+=("$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")")
    fi
    for c in "${candidates[@]}"; do
        if [ -x "$c/bin/java" ] && [ -x "$c/bin/javac" ] \
            && "$c/bin/java" -version 2>&1 | grep -q 'version "11\.'; then
            echo "$c"
            return 0
        fi
    done
    return 1
}

find_maven() {
    local c
    if command -v mvn >/dev/null; then command -v mvn; return 0; fi
    for c in "$HOME"/tools/apache-maven-*/bin/mvn; do
        [ -x "$c" ] && { echo "$c"; return 0; }
    done
    return 1
}

preflight() {
    local missing=()
    for tool in curl python3 setsid; do
        command -v "$tool" >/dev/null || missing+=("$tool")
    done
    [ ${#missing[@]} -eq 0 ] || die "Missing tools: ${missing[*]}. On Ubuntu/WSL: sudo apt install ${missing[*]}"

    if on_windows_fs "$ROOT"; then
        die "The repository is on the Windows file system ($ROOT).
Under WSL, files there go through a slow network file system: Jira takes 30+ minutes to
start, usually times out, and its database can be corrupted. Clone the repository inside
the Linux file system instead, for example:
    git clone -b dev/test-data https://github.com/garfield-arlene/sprint-pulse.git ~/sprint-pulse
    cd ~/sprint-pulse && scripts/dev-jira.sh start"
    fi
    if grep -q $'\r' "$ROOT/scripts/seed_test_data.py" "$PLUGIN_DIR/pom.xml" 2>/dev/null; then
        note "WARNING: files in this checkout have Windows (CRLF) line endings. If anything fails, run:"
        note "    git config core.autocrlf input && git rm -rq --cached . && git reset --hard"
    fi

    JAVA_HOME=$(find_java11) || die "JDK 11 not found. Jira 9.4 only runs on Java 8 or 11.
Install it, then run this script again (or set JAVA_HOME to it):
    Ubuntu/WSL: sudo apt install openjdk-11-jdk-headless
    Other:      https://adoptium.net/temurin/releases/?version=11"
    export JAVA_HOME
    export PATH=$JAVA_HOME/bin:$PATH
    MVN=$(find_maven) || die "Maven not found. Install Maven 3.8 or later (Ubuntu/WSL: sudo apt install maven)."

    local mem_kb
    mem_kb=$(awk '/MemTotal/ {print $2}' /proc/meminfo 2>/dev/null || echo 0)
    if [ "$mem_kb" -gt 0 ] && [ "$mem_kb" -lt 4000000 ]; then
        note "WARNING: only $((mem_kb / 1024)) MB of memory. Jira needs about 2 GB free and may fail to start."
        is_wsl && note "         Give WSL more memory in %UserProfile%\\.wslconfig ([wsl2] memory=8GB), then run: wsl --shutdown"
    fi
    mkdir -p "$STATE_DIR"
}

mvn_cmd() {
    "$MVN" -B -ntp -gs "$ROOT/scripts/maven-settings.xml" "$@"
}

# The local Jira's Tomcat needs javax.transaction:jta:1.0.1B, which no public repository has.
# jta 1.1 is API compatible, so install it under that version once.
ensure_jta() {
    local repo
    repo=$(cd "$PLUGIN_DIR" && mvn_cmd -q help:evaluate -Dexpression=settings.localRepository -DforceStdout 2>/dev/null | tail -1)
    [ -n "$repo" ] || repo=$HOME/.m2/repository
    [ -f "$repo/javax/transaction/jta/1.0.1B/jta-1.0.1B.jar" ] && return 0
    note "Installing javax.transaction:jta:1.0.1B into the local Maven repository (one time) ..."
    (cd "$PLUGIN_DIR" && mvn_cmd -q dependency:get -Dartifact=javax.transaction:jta:1.1 \
        && mvn_cmd -q install:install-file -DgroupId=javax.transaction -DartifactId=jta \
            -Dversion=1.0.1B -Dpackaging=jar -DgeneratePom=true \
            -Dfile="$repo/javax/transaction/jta/1.1/jta-1.1.jar") \
        || die "Could not install jta 1.0.1B. Check the network connection to Maven Central."
}

# --- process handling ---------------------------------------------------------------------

jira_up() {
    curl -fsS -m 10 "$JIRA_URL/status" 2>/dev/null | grep -q '"RUNNING"'
}

port_busy() {
    (exec 3<>/dev/tcp/127.0.0.1/2990) 2>/dev/null
}

our_pgid() {
    local pgid
    pgid=$(cat "$PID_FILE" 2>/dev/null) || return 1
    [ -n "$pgid" ] && kill -0 -- "-$pgid" 2>/dev/null && echo "$pgid"
}

explain_failure() {
    note ""
    note "Last lines of $LOG:"
    tail -n 40 "$LOG" | sed 's/^/    /'
    note ""
    if grep -q 'wagon-http.*atlassian\|Could not find artifact' "$LOG"; then
        note "A Maven download failed. Check the network (and any proxy) can reach"
        note "https://packages.atlassian.com/maven-public/ and https://repo.maven.apache.org/maven2/."
    elif grep -q 'Address already in use' "$LOG"; then
        note "Port 2990 (or 8005/5005) is already used by another program."
    elif grep -q 'UnsupportedClassVersionError\|Unsupported class file' "$LOG"; then
        note "The wrong Java version was used. Set JAVA_HOME to a JDK 11."
    elif grep -qi 'OutOfMemoryError\|Cannot allocate memory' "$LOG"; then
        note "Jira ran out of memory. Close other programs or give WSL more memory (.wslconfig)."
    elif grep -q 'failed to finish deploying within the timeout' "$LOG"; then
        note "Jira took too long to start. This happens when the repository is on a slow disk or the"
        note "Windows file system, or when Jira's database was damaged (for example by editing it by hand)."
        note "To start over with a brand-new Jira and fresh test data (the current one is kept as a backup):"
        note "    $0 fresh"
    elif grep -q 'Database may be already in use\|acquire lock file for jira.home' "$LOG"; then
        note "Jira's database is locked, usually by a Jira that is still running. Run: $0 stop"
    fi
    exit 1
}

start_jira() {
    if jira_up; then
        note "Jira is already running at $JIRA_URL"
        return 0
    fi
    if our_pgid >/dev/null; then
        note "Jira is still starting (log: $LOG)."
    else
        port_busy && die "Port 2990 is in use, but not by a running Jira. Stop that program first."
        ensure_jta
        [ -d "$PLUGIN_DIR/target/jira/home" ] \
            && note "Starting Jira with the existing data in my-jira-gadget/target/jira ..." \
            || note "No local Jira yet: creating a new one (the first run downloads Jira and takes 10+ minutes) ..."
        cd "$PLUGIN_DIR"
        # jira:run stops Jira when its standard input closes, so give it one that never closes.
        # setsid puts Maven and Jira in their own process group, so `stop` can find them all.
        setsid bash -c 'tail -f /dev/null | exec "$@"' _ \
            "$MVN" -B -ntp -gs "$ROOT/scripts/maven-settings.xml" jira:run -DskipTests \
            -Dproduct.start.timeout=$((START_TIMEOUT * 1000)) \
            > "$LOG" 2>&1 < /dev/null &
        echo $! > "$PID_FILE"
        cd - >/dev/null
    fi

    note "Waiting for Jira (log: $LOG) ..."
    local started=$SECONDS waited=0 reported=0
    until jira_up; do
        our_pgid >/dev/null || { note "Jira stopped while starting."; explain_failure; }
        if grep -q 'BUILD FAILURE' "$LOG" 2>/dev/null; then
            note "Maven reported a build failure."; stop_jira quiet; explain_failure
        fi
        [ "$waited" -ge "$START_TIMEOUT" ] && { note "Jira did not start within ${START_TIMEOUT}s."; explain_failure; }
        sleep 10; waited=$((SECONDS - started))
        if [ $((waited / 60)) -gt "$reported" ]; then
            reported=$((waited / 60)); note "  still starting ($reported min) ..."
        fi
    done
    note "Jira is running at $JIRA_URL (admin / admin)."
}

stop_jira() {
    local pgid
    if ! pgid=$(our_pgid); then
        rm -f "$PID_FILE"
        if jira_up; then
            die "A Jira is running at $JIRA_URL, but it was not started by this script. Stop it where it was started."
        fi
        [ "${1:-}" = quiet ] || note "Jira is not running."
        return 0
    fi
    [ "${1:-}" = quiet ] || note "Stopping Jira ..."
    # Closing jira:run's input makes Maven shut Jira down cleanly, which keeps its database intact.
    pkill -g "$pgid" -x tail 2>/dev/null || true
    local i
    for i in $(seq 1 90); do
        kill -0 -- "-$pgid" 2>/dev/null || break
        sleep 2
    done
    if kill -0 -- "-$pgid" 2>/dev/null; then
        kill -TERM -- "-$pgid" 2>/dev/null || true
        sleep 15
        kill -KILL -- "-$pgid" 2>/dev/null || true
    fi
    rm -f "$PID_FILE"
    [ "${1:-}" = quiet ] || note "Jira stopped."
}

fresh() {
    stop_jira quiet
    if [ -d "$PLUGIN_DIR/target/jira" ]; then
        local dest
        dest=$BACKUP_DIR/jira-$(date +%Y%m%d-%H%M%S)
        mkdir -p "$BACKUP_DIR"
        note "Moving the current Jira to $dest ..."
        mv "$PLUGIN_DIR/target/jira" "$dest"
    fi
    start_jira
    seed
}

seed() {
    python3 "$ROOT/scripts/seed_test_data.py" --url "$JIRA_URL" "$@"
}

case "${1:-}" in
    start)  preflight; start_jira; seed
            note ""; note "Log in as admin / admin and open the Sprint Pulse dashboard link above." ;;
    stop)   stop_jira ;;
    status) if jira_up; then note "Jira is running at $JIRA_URL"
            elif our_pgid >/dev/null; then note "Jira is starting (log: $LOG)"
            else note "Jira is not running."; fi ;;
    seed)   jira_up || die "Jira is not running. Use: $0 start"; seed ;;
    fresh)  preflight; fresh
            note ""; note "Log in as admin / admin and open the Sprint Pulse dashboard link above." ;;
    reset)  jira_up || die "Jira is not running. Use: $0 start"; seed --reset ;;
    logs)   exec tail -n 100 -f "$LOG" ;;
    *)      sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
