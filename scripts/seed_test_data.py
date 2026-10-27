#!/usr/bin/env python3
"""Load the Sprint Pulse test data into a local Jira Software instance.

Creates two Scrum projects with boards, sprints, issues, story points, a blocking link and
impediment flags, plus a "Sprint Pulse" dashboard with the gadget on it. Sprint dates are set
relative to today, so the active sprints are always in progress.

The script is safe to run repeatedly:

- A project that this script finished loading is left unchanged.
- A project that this script started but did not finish (for example because Jira stopped
  half way) is deleted and loaded again.
- A project with the same key that this script did not create is never touched.

Usage: seed_test_data.py [--reset] [--url URL] [--user USER] [--password PASSWORD]

--reset deletes the SP and MOB projects (and their boards) first and loads them again.
Only uses the Python standard library.
"""
import argparse
import base64
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta

MARKER = "sprint-pulse-seed"
GADGET_SPEC = ("rest/gadgets/1.0/g/com.garfieldArlene.jira.gadget.sprint-pulse:sprint-pulse"
               "/gadget/my-gadget.xml")
DASHBOARD_NAME = "Sprint Pulse"

# Each issue: (summary, issue type, story points or None). Bugs and tasks cannot hold story
# points on the Scrum template's screens, so they have none.
PROJECTS = [
    {
        "key": "SP",
        "name": "Sprint Pulse Test",
        # Expected gadget health for the active sprint, checked after loading.
        "expect": {"committedStoryPoints": 21, "completedStoryPoints": 8, "addedStoryPoints": 3,
                   "removedStoryPoints": 5, "totalIssues": 6, "completedIssues": 3},
        "plan": "sp",
    },
    {
        "key": "MOB",
        "name": "Mobile App",
        "expect": {"committedStoryPoints": 21, "completedStoryPoints": 3, "addedStoryPoints": 0,
                   "removedStoryPoints": 0, "totalIssues": 6, "completedIssues": 1},
        "plan": "mob",
    },
]


class JiraError(Exception):
    pass


class Jira:
    def __init__(self, url, user, password):
        self.url = url.rstrip("/")
        token = base64.b64encode(f"{user}:{password}".encode()).decode()
        self.headers = {"Authorization": "Basic " + token, "Accept": "application/json",
                        "X-Atlassian-Token": "no-check"}

    def call(self, method, path, body=None, ok=(200, 201, 204), form=False, retries=5):
        url = self.url + path
        headers = dict(self.headers)
        data = None
        if body is not None:
            if form:
                data = urllib.parse.urlencode(body).encode()
                headers["Content-Type"] = "application/x-www-form-urlencoded"
            else:
                data = json.dumps(body).encode()
                headers["Content-Type"] = "application/json"
        for attempt in range(retries):
            req = urllib.request.Request(url, data=data, headers=headers, method=method)
            try:
                with urllib.request.urlopen(req, timeout=120) as resp:
                    status, text = resp.status, resp.read().decode("utf-8", "replace")
            except urllib.error.HTTPError as e:
                status, text = e.code, e.read().decode("utf-8", "replace")
            except (urllib.error.URLError, ConnectionError, TimeoutError) as e:
                status, text = None, str(e)
            # Jira answers 503 and drops connections while it is still starting or busy.
            if status in ok:
                return json.loads(text) if text.strip().startswith(("{", "[")) else text
            if status is not None and status not in (500, 502, 503, 504):
                break
            time.sleep(3 * (attempt + 1))
        raise JiraError(f"{method} {path} failed ({status}): {text[:400]}")

    def get(self, path, **kw):
        return self.call("GET", path, **kw)

    def post(self, path, body=None, **kw):
        return self.call("POST", path, body if body is not None else {}, **kw)

    def put(self, path, body, **kw):
        return self.call("PUT", path, body, **kw)

    def delete(self, path, **kw):
        return self.call("DELETE", path, **kw)


def log(msg):
    print(msg, flush=True)


def jira_date(days):
    """A Jira Agile date `days` from now (negative for the past), in the local time zone."""
    return (datetime.now().astimezone() + timedelta(days=days)).strftime("%Y-%m-%dT%H:%M:%S.000%z")


def wait_until_ready(jira, timeout):
    """Wait until both the core REST API and Jira Software's agile API answer."""
    deadline = time.time() + timeout
    last = ""
    while time.time() < deadline:
        try:
            jira.get("/rest/api/2/serverInfo", retries=1)
            jira.get("/rest/agile/1.0/board?maxResults=1", retries=1)
            return
        except JiraError as e:
            last = str(e)
        time.sleep(5)
    raise JiraError(f"Jira at {jira.url} did not become ready within {timeout}s. Last error: {last}")


class Seeder:
    def __init__(self, jira):
        # Jira Software creates its Story Points, Sprint and Flagged fields along with the first
        # Scrum project, so a brand-new Jira has none of them yet.
        self.jira = jira
        self.board = None

    # --- project state -------------------------------------------------------------------

    def marker(self, key):
        """The seed marker on a project: None (not ours), False (unfinished) or True (done)."""
        try:
            prop = self.jira.get(f"/rest/api/2/project/{key}/properties/{MARKER}", ok=(200,), retries=1)
        except JiraError as e:
            if "(404)" in str(e):
                return None
            raise
        return bool(prop.get("value", {}).get("complete"))

    def set_marker(self, key, complete):
        self.jira.put(f"/rest/api/2/project/{key}/properties/{MARKER}",
                      {"complete": complete, "loaded": datetime.now().astimezone().isoformat()})

    def project_exists(self, key):
        try:
            self.jira.get(f"/rest/api/2/project/{key}", ok=(200,), retries=1)
            return True
        except JiraError as e:
            if "(404)" in str(e):
                return False
            raise

    def boards_for(self, key):
        data = self.jira.get(f"/rest/agile/1.0/board?projectKeyOrId={key}&maxResults=50")
        return data.get("values", [])

    def delete_project(self, key):
        for board in self.boards_for(key):
            log(f"  deleting board {board['id']} ({board['name']})")
            self.jira.delete(f"/rest/agile/1.0/board/{board['id']}")
        log(f"  deleting project {key}")
        self.jira.delete(f"/rest/api/2/project/{key}")

    def create_project(self, key, name):
        self.jira.post("/rest/api/2/project", {
            "key": key, "name": name, "projectTypeKey": "software", "lead": "admin",
            "projectTemplateKey": "com.pyxis.greenhopper.jira:gh-scrum-template"})
        self.set_marker(key, False)
        # The Scrum template creates the board right after the project; give it a moment.
        for _ in range(30):
            boards = [b for b in self.boards_for(key) if b["type"] == "scrum"]
            if boards:
                self.board = boards[0]["id"]
                return
            time.sleep(2)
        raise JiraError(f"No Scrum board appeared for project {key}")

    # --- building blocks -----------------------------------------------------------------

    def issue(self, key, summary, issue_type, points=None):
        created = self.jira.post("/rest/api/2/issue", {"fields": {
            "project": {"key": key}, "summary": summary, "issuetype": {"name": issue_type}}})
        issue_key = created["key"]
        if points is not None:
            self.jira.put(f"/rest/agile/1.0/issue/{issue_key}/estimation?boardId={self.board}",
                          {"value": str(points)})
        return issue_key

    def sprint(self, name, start=None, end=None):
        body = {"name": name, "originBoardId": self.board}
        if start is not None:
            body.update(startDate=jira_date(start), endDate=jira_date(end))
        return self.jira.post("/rest/agile/1.0/sprint", body)["id"]

    def add_to_sprint(self, sprint_id, keys):
        self.jira.post(f"/rest/agile/1.0/sprint/{sprint_id}/issue", {"issues": list(keys)})

    def to_backlog(self, keys):
        self.jira.post("/rest/agile/1.0/backlog/issue", {"issues": list(keys)})

    def start(self, sprint_id, start, end):
        self.jira.post(f"/rest/agile/1.0/sprint/{sprint_id}", {
            "state": "active", "startDate": jira_date(start), "endDate": jira_date(end)})

    def close(self, sprint_id):
        self.jira.post(f"/rest/agile/1.0/sprint/{sprint_id}", {"state": "closed"})

    def move(self, issue_key, status):
        transitions = self.jira.get(f"/rest/api/2/issue/{issue_key}/transitions")["transitions"]
        match = [t for t in transitions if t["to"]["name"].lower() == status.lower()]
        if not match:
            names = ", ".join(t["to"]["name"] for t in transitions)
            raise JiraError(f"{issue_key} cannot move to '{status}' (available: {names})")
        self.jira.post(f"/rest/api/2/issue/{issue_key}/transitions",
                       {"transition": {"id": match[0]["id"]}})

    def blocks(self, blocker, blocked):
        self.jira.post("/rest/api/2/issueLink", {
            "type": {"name": "Blocks"}, "inwardIssue": {"key": blocker},
            "outwardIssue": {"key": blocked}})

    def flag(self, issue_key):
        self.jira.post("/rest/greenhopper/1.0/xboard/issue/flag/flag.json",
                       {"issueKeys": [issue_key], "flag": True})

    # --- the two projects ----------------------------------------------------------------

    def load_sp(self):
        """SP: a closed sprint, an active sprint with scope change and blockers, a future sprint."""
        s0 = self.sprint("SP Sprint 0")
        self.start(s0, -20, -6)
        self.close(s0)

        s1 = self.sprint("SP Sprint 1")
        committed = [
            self.issue("SP", "Login page", "Story", 3),
            self.issue("SP", "Dashboard layout", "Story", 5),
            self.issue("SP", "Fix crash on save", "Bug"),
            self.issue("SP", "API pagination", "Story", 8),
            self.issue("SP", "Update docs", "Task"),
            self.issue("SP", "Search filters", "Story", 5),
        ]
        login, dashboard, crash, pagination, _docs, search = committed
        self.add_to_sprint(s1, committed)
        self.start(s1, -6, 8)
        # Scope change after the sprint started: one story added, one removed.
        time.sleep(2)
        hotfix = self.issue("SP", "Hotfix export", "Story", 3)
        self.add_to_sprint(s1, [hotfix])
        self.to_backlog([search])
        # Progress, a blocking link from a backlog task, and an impediment flag.
        for key in (login, crash, dashboard):
            self.move(key, "Done")
        self.move(hotfix, "In Progress")
        gateway = self.issue("SP", "Gateway rate-limit config", "Task")
        self.blocks(gateway, pagination)
        self.flag(hotfix)

        self.sprint("SP Sprint 2", 8, 22)
        return s1

    def load_mob(self):
        """MOB: a second board, so the gadget shows its board selector."""
        s1 = self.sprint("MOB Sprint 1")
        onboarding = self.issue("MOB", "Onboarding screens", "Story", 5)
        push = self.issue("MOB", "Push notification setup", "Story", 3)
        rotate = self.issue("MOB", "Crash on rotate", "Bug")
        self.add_to_sprint(s1, [onboarding, push, rotate])
        self.start(s1, -19, -5)
        self.move(onboarding, "Done")
        self.move(rotate, "Done")
        self.close(s1)

        s2 = self.sprint("MOB Sprint 2")
        offline = self.issue("MOB", "Offline sync", "Story", 8)
        dark = self.issue("MOB", "Dark mode", "Story", 5)
        token = self.issue("MOB", "Login token refresh", "Story", 3)
        polish = self.issue("MOB", "Settings page polish", "Story", 2)
        leak = self.issue("MOB", "Fix image cache leak", "Bug")
        self.add_to_sprint(s2, [offline, dark, token, polish, leak, push])
        self.start(s2, -5, 9)
        self.move(offline, "In Progress")
        self.move(token, "Done")
        self.flag(dark)

        self.sprint("MOB Sprint 3")
        return s2

    # --- dashboard -----------------------------------------------------------------------

    def ensure_dashboard(self):
        """Create an admin dashboard with the Sprint Pulse gadget unless one already exists."""
        dashboards = self.jira.get("/rest/api/2/dashboard?filter=my&maxResults=100")["dashboards"]
        for dash in dashboards:
            if dash["name"] == DASHBOARD_NAME:
                log(f"Dashboard '{DASHBOARD_NAME}' already exists: "
                    f"{self.jira.url}/secure/Dashboard.jspa?selectPageId={dash['id']}")
                return
        self.jira.post("/secure/AddPortalPage.jspa", {
            "portalPageName": DASHBOARD_NAME, "portalPageDescription": "Sprint Pulse test data",
            "favourite": "true", "Add": "Add"}, ok=(200, 302), form=True)
        dashboards = self.jira.get("/rest/api/2/dashboard?filter=my&maxResults=100")["dashboards"]
        dash = next((d for d in dashboards if d["name"] == DASHBOARD_NAME), None)
        if dash is None:
            raise JiraError("Creating the dashboard did not work")
        # This internal endpoint adds the gadget but can answer 500 while rendering its reply,
        # so check the dashboard afterwards instead of trusting the status code.
        self.jira.post(f"/rest/dashboards/1.0/{dash['id']}",
                       {"type": "gadget", "dashboardItemId": GADGET_SPEC, "columnIndex": 0},
                       ok=(200, 201, 500), retries=1)
        gadgets = self.jira.get(f"/rest/dashboards/1.0/{dash['id']}.json").get("gadgets", [])
        if not any(g.get("gadgetSpecUrl", "").endswith(GADGET_SPEC) for g in gadgets):
            raise JiraError("Adding the Sprint Pulse gadget to the dashboard did not work. "
                            "Is the plugin installed?")
        log(f"Created dashboard '{DASHBOARD_NAME}' with the Sprint Pulse gadget: "
            f"{self.jira.url}/secure/Dashboard.jspa?selectPageId={dash['id']}")


def check_gadget(jira, project, sprint_id):
    """Compare the gadget's health figures with the expected ones. Returns True when they match."""
    try:
        data = jira.get(f"/rest/sprint/1.0/data?sprintId={sprint_id}", ok=(200,), retries=3)
    except JiraError as e:
        log(f"  could not check the gadget data for {project['key']}: {e}")
        return False
    health = data.get("health", {})
    wrong = {k: (v, health.get(k)) for k, v in project["expect"].items() if health.get(k) != v}
    if wrong:
        details = ", ".join(f"{k} expected {v[0]} got {v[1]}" for k, v in wrong.items())
        log(f"  WARNING: {project['key']} gadget figures differ: {details}")
        return False
    log("  gadget figures OK: " +", ".join(f"{k}={health[k]}" for k in project["expect"]))
    return True


def active_sprint(jira, key):
    boards = jira.get(f"/rest/agile/1.0/board?projectKeyOrId={key}")["values"]
    for board in boards:
        sprints = jira.get(f"/rest/agile/1.0/board/{board['id']}/sprint?state=active")["values"]
        if sprints:
            return sprints[0]["id"]
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--url", default="http://localhost:2990/jira")
    parser.add_argument("--user", default="admin")
    parser.add_argument("--password", default="admin")
    parser.add_argument("--reset", action="store_true",
                        help="delete the SP and MOB projects first and load them again")
    parser.add_argument("--wait", type=int, default=900,
                        help="seconds to wait for Jira to become ready (default 900)")
    args = parser.parse_args()

    jira = Jira(args.url, args.user, args.password)
    log(f"Waiting for Jira at {args.url} ...")
    wait_until_ready(jira, args.wait)
    seeder = Seeder(jira)
    all_ok = True

    for project in PROJECTS:
        key = project["key"]
        exists = seeder.project_exists(key)
        marker = seeder.marker(key) if exists else None
        if exists and args.reset:
            log(f"{key}: --reset given, deleting it first")
            seeder.delete_project(key)
        elif exists and marker is None:
            log(f"{key}: project already exists and was not created by this script; leaving it unchanged.")
            continue
        elif exists and marker is True:
            log(f"{key}: test data already loaded.")
            sprint_id = active_sprint(jira, key)
            if sprint_id is not None:
                all_ok &= check_gadget(jira, project, sprint_id)
            continue
        elif exists:
            log(f"{key}: an earlier load did not finish; deleting it and loading again")
            seeder.delete_project(key)

        log(f"{key}: loading test data ...")
        seeder.create_project(key, project["name"])
        sprint_id = getattr(seeder, "load_" + project["plan"])()
        seeder.set_marker(key, True)
        log(f"{key}: done (board {seeder.board}, active sprint {sprint_id})")
        # The gadget caches sprint reports for 30 seconds; this is the first request, so it is fresh.
        all_ok &= check_gadget(jira, project, sprint_id)

    seeder.ensure_dashboard()
    log("Test data is ready." if all_ok else "Test data is loaded, but see the warnings above.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except JiraError as e:
        print(f"ERROR: {e}", file=sys.stderr)
        sys.exit(1)
