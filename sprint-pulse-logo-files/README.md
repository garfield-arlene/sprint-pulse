<h1 align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="branding/sprint-pulse-logo-dark.svg">
    <img src="branding/sprint-pulse-logo.svg" alt="Sprint Pulse" width="420">
  </picture>
</h1>

A Jira Server/Data Center dashboard gadget that combines the ideas of **Sprint Health** and **Sprint Statistics** into a single view: sprint health metrics at the top, and all sprint issues grouped by status with story points below.

![Sprint Pulse gadget with the board selector open](board-selector.png)

## Features

- **Board selector** next to the sprint picker, shown when more than one sprint board is visible. It scopes the sprint list to a single board, or shows all boards, and remembers the choice per user.
- **Sprint picker** with dates and state (active, closed, future). The running sprint is selected by default, and at most the 20 most recent closed sprints are listed.
- **Health summary cards** for:
  - Committed story points
  - Completed story points
  - Story points added and removed during the sprint (scope change)
  - Blocked and flagged issue counts
  - Total/completed issue counts
  - Story-point completion percentage with a progress bar
- **Issues by status**, grouped and collapsible, showing key, summary, type, story points, assignee, priority, and blocked/flagged indicators.
- **Per-group totals** and a sprint summary row (total SP, issue count, completed SP).
- **Blocked/flagged detection** from issue links, custom fields, and labels.
- **Auto-refresh** (every 30 seconds by default, configurable or off) that keeps the metrics live, with a last-updated timestamp and no polling while the browser tab is hidden. The interval is selectable in the gadget itself, next to the Refresh button, so it can be changed even when the gadget's configuration form is not available.
- **Permission-aware**: all queries run as the logged-in user, so only issues that user can see are returned.
- Read-only: no Jira data is modified.

## Requirements

- Jira Software 9.4.0 (the plugin is built against this version)
- JDK 11
- Maven 3.8+
- A Jira project using sprints (Jira Software) and, ideally, a numeric Story Points field

## Build

```bash
cd my-jira-gadget
mvn package
```

The installable plugin is written to `my-jira-gadget/target/sprint-pulse-<version>.jar` (an OBR file is also generated).

Run a local Jira instance with the plugin installed:

```bash
mvn jira:run
```

Jira starts at <http://localhost:2990/jira> with `admin` / `admin`. Use `mvn jira:debug` to start it in debug mode. The first run can take a while because the Jira distribution is downloaded and initialized.

## Installation

1. Build the plugin (see above).
2. In Jira, go to **Administration > Manage apps** (or **Apps > Manage apps**).
3. Choose **Upload app** and select `target/sprint-pulse-<version>.jar`.
4. Wait for the plugin to install and enable.

### Manual/offline alternative

Copy the JAR into `<jira-home>/plugins/installed-plugins/` and restart Jira.

## Usage

1. Open or create a dashboard and choose **Add gadget**.
2. Search for **Sprint Pulse** and add it.
3. Optionally pick a board from the dropdown next to the sprint list to limit it to a single board (also settable as **Board ID** in the configuration dialog). `All boards` lists sprints from every board you can see. The dropdown hides itself when only one board is available or the board list cannot be loaded, in which case the sprint list keeps working.
4. Select a sprint from the dropdown. It lists the running sprint, sprints still to come, and the 20 most recent closed sprints on the visible boards. The gadget loads health metrics and issues automatically; the running sprint is preselected, so the gadget shows data as soon as it loads.

   ![Sprint picker with the running sprint selected](sprint-selector.png)
5. Click **Refresh** to reload the selected sprint on demand. By default the gadget also refreshes itself every 30 seconds; the last-updated time next to the button shows when the data was fetched. The dropdown next to the button sets the refresh interval (`Auto off`, `Every 30s`, `Every 1m`, `Every 5m`) and is remembered per user.

The selected sprint is saved as a gadget preference. Other preferences (shown in the configuration dialog) include:

| Preference | Default | Purpose |
| --- | --- | --- |
| Sprint | none | Sprint to display |
| Board ID | empty | Filter available sprints to one rapid view/board; also changeable from the board dropdown in the gadget |
| Show Health Summary | `true` | Show or hide the health metric cards |
| Show Issues Table | `true` | Show or hide the issues table |
| Group by Status | `true` | Group issues by workflow status (grouping is currently always applied) |
| Auto refresh | 30 seconds | How often the gadget reloads the selected sprint (`30` seconds, `60` seconds, `300` seconds, or `Off`) |

### Selecting story points

The gadget uses the first custom field named **Story Points** or **Story Point Estimate**. If neither exists, it falls back to the first numeric (`float`) custom field. Make sure the field is on the issues in the sprint, otherwise story-point totals will be zero.

### How health metrics are calculated

- **Committed**: story points currently in the sprint, minus points added after sprint start, plus points removed from the sprint.
- **Completed**: story points on issues whose status category is **Done**.
- **Added/Removed**: read from the GreenHopper sprint report, including the story points of issues added to or removed from the sprint; issue counts come from the report's added/punted issue lists.
- **Story points** are summed from all four issue lists in the sprint report (completed, incomplete, added, removed), so points on issues that were added and then removed still count toward the added/removed metrics.
- **Blocked**: issue has an issue link whose type name contains `block` or `imped`, or a populated custom field whose name contains `flag`, `impediment`, or `blocked`, or a `flagged`/`impediment` label.
- **Flagged**: a populated **Flagged** or **Impediment Flag** custom field, or a `flagged`/`impediment` label.

## REST API

The gadget is backed by a REST module at `/rest/sprint/1.0`.

| Method | Path | Query parameters | Description |
| --- | --- | --- | --- |
| `GET` | `/rest/sprint/1.0/boards` | – | Boards with sprint support that the user can see, sorted by name |
| `GET` | `/rest/sprint/1.0/sprints` | `boardId` (optional) | Active and future sprints plus the 20 most recent closed ones (newest first); pass `boardId` to scope the list to one board |
| `GET` | `/rest/sprint/1.0/data` | `sprintId` or `boardId` | Sprint, health metrics, and issues grouped by status |

Example:

```bash
curl -u user:token "https://jira.example.com/rest/sprint/1.0/data?sprintId=1"
```

Sprints are discovered through the GreenHopper (Jira Software) Spring beans, located via the application context that GreenHopper publishes as an OSGi service: `SprintManager` for single sprint lookups and `RapidViewService` (`getRapidViews`/`getRapidView` + `getSprintsForView`) for board lists. Board visibility and `isSprintSupportEnabled` are honoured, so Kanban boards are excluded. If GreenHopper is unavailable, the gadget falls back to the **Sprint** custom field on issues visible to the current user (`sprint is not EMPTY`). Single-sprint lookups by `sprintId` are not gated by board visibility, so a sprint keeps loading after it leaves a board. Sprint reports are generated in-process and cached for 30 seconds (up to 20 sprints) to keep auto-refresh cheap.

## Project structure

```
my-jira-gadget/
├── pom.xml
└── src/main/
    ├── java/com/garfieldArlene/jira/gadget/rest/SprintGadgetResource.java
    └── resources/
        ├── atlassian-plugin.xml
        ├── gadget/my-gadget.xml
        ├── gadget/gadget.properties
        ├── images/
        └── META-INF/spring/plugin-context.xml
branding/
```

- `SprintGadgetResource.java` — REST endpoints, sprint discovery, health calculations, issue serialization.
- `my-gadget.xml` — gadget definition, preferences, styles, and client-side rendering.
- `atlassian-plugin.xml` — plugin descriptor (gadget and REST modules).
- `images/` — the plugin icon, plugin logo and gadget thumbnail.
- `branding/` — logo source files and usage notes (see [branding/README.md](branding/README.md)).
- `tests/gadget-smoke-test.js` — runs the gadget JavaScript in a stubbed DOM/fetch sandbox to check loading, auto-refresh, preferences, and the board selector.

## CI pipeline

The GitHub Actions workflow in `.github/workflows/build.yml` runs automatically on every push to any branch that contains it. It also supports on-demand runs from **Actions > Build > Run workflow**; the button only appears when the workflow exists on the repository's default branch. It runs two gates in parallel before building:

- **Lint** — validates the Maven project and XML descriptors, syntax-checks the gadget JavaScript, smoke-tests it against a stubbed DOM, and runs Checkstyle (report uploaded as the `checkstyle-report` artifact).
- **Security scan** — runs Trivy against dependencies and the repository for `CRITICAL`/`HIGH` vulnerabilities and secrets.

The build job then packages the plugin and uploads the JAR and OBR as the `sprint-pulse-plugin` artifact.

## Development notes

- `node tests/gadget-smoke-test.js` (Node 20, no dependencies) exercises the gadget JavaScript locally; CI runs the same script.
- QuickReload is enabled (`enableQuickReload`), so `mvn jira:run` picks up many changes without a restart. Changes to `atlassian-plugin.xml` or Spring wiring normally still require a restart.
- `cdp-verify.ps1` is a Windows PowerShell helper that uses the Chrome DevTools Protocol to open a dashboard in a running Jira instance, verify the health cards render, and save a screenshot:

  ```powershell
  ./cdp-verify.ps1 -Url "http://localhost:2990/jira/dashboards/10000" -OutFile "dash-health.png"
  ```

  Start Chrome/Edge with `--remote-debugging-port=9222` before running it.

## License

BSD 3-Clause. See [LICENSE](LICENSE).
