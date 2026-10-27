# Sprint Pulse security and data-handling statement

This statement describes how the Sprint Pulse dashboard gadget for Jira Software Data Center handles data, network access and permissions. It applies to Sprint Pulse 3.1.1 and later. Each statement can be checked against the source code in this repository; the relevant code is named in each section.

## Summary

- **No outbound network connections.** The plugin opens no network connections from the Jira server, and the gadget's browser code only calls the Jira instance it is installed on.
- **No data of its own.** The plugin creates no database tables, plugin settings or files. The only values it saves are the viewer's own gadget preferences, which Jira stores like any other gadget's.
- **Read-only.** The plugin never creates, changes or deletes issues, sprints, boards or any other Jira data.
- **Runs with the viewer's permissions.** Every query runs as the logged-in user, so the gadget shows only what that user can already see in Jira.
- **Works offline.** There is no license check, activation, telemetry or update check. The plugin installs from a jar file and works on networks with no internet access.

## Network access

**Server side.** The plugin's REST resource (`SprintGadgetResource.java`) reads data only through Jira's own Java APIs and the Jira Software (GreenHopper) services running in the same Jira process. It contains no HTTP clients, sockets or other network code.

**Browser side.** The gadget's JavaScript (`gadget/my-gadget.xml`) calls only the plugin's REST endpoints on the same Jira instance, using the viewer's existing Jira session. It loads no scripts, stylesheets, fonts or images from any other host.

## Data storage

| Data | Where it is kept | Contents | Lifetime |
| --- | --- | --- | --- |
| Gadget preferences | Jira's dashboard storage, with the gadget on its dashboard | Selected sprint id and board id, whether the health summary and issues table are shown, grouping, and the auto-refresh interval | Until the gadget is removed or the preferences are changed |
| Sprint report cache | Memory of the Jira node that served the request | Issue keys and story points used for the added and removed scope figures, for up to 20 sprints | 30 seconds per entry; cleared when Jira or the plugin restarts |
| Log messages | Jira's application log | Warnings when Jira Software services cannot be reached, which may include board ids, sprint ids and issue keys | Jira's log retention |

The plugin does not copy issue data anywhere else. It sends issue details only to the viewer's browser, in response to that viewer's request.

## Data access and permissions

- **Authentication.** The REST endpoints (`/rest/sprint/1.0/boards`, `/sprints` and `/data`) require a logged-in Jira user. Anonymous requests are rejected.
- **Issues.** Issues are found with a JQL search (`sprint = <id>`) that runs as the logged-in user, so Jira's issue security and project permissions apply.
- **Boards.** Board lists come from Jira Software's own board service for the logged-in user, so only boards the user can see are listed.
- **Sprints.** A sprint requested by id is returned only when the user can see its board or at least one of its issues. Otherwise the endpoint answers "Sprint not found", the same as for a sprint that does not exist.
- **Requests.** All endpoints are read-only `GET` requests.

## Browser security

Issue summaries, assignee names, status, issue type and priority names, issue keys and error messages are user-controlled text. The gadget escapes all of them before displaying them, so they always appear as plain text and are never interpreted as HTML or script. The automated gadget test (`tests/gadget-smoke-test.js`) checks this on every build.

## Dependencies

The plugin bundles one third-party library, Google Gson 2.10.1, for building JSON responses. All other libraries are provided by Jira at runtime. Every push is scanned with Trivy for known vulnerabilities and committed secrets.

## Installation

The plugin installs from its jar file through **Manage apps > Upload app**, or by copying the jar into Jira's `installed-plugins` directory. Installation needs no internet access, no Atlassian Marketplace account and no license key.

## Changes in 3.1.1

Version 3.1.1 fixed three problems found while preparing this statement. Earlier versions are affected:

- **Cross-site scripting.** Issue summaries and other Jira text were displayed without escaping, so a crafted issue summary could run script in the browser of anyone viewing the gadget.
- **Loopback HTTP request.** When the in-process sprint report could not be built, the plugin requested it over HTTP from an address built from the incoming request, forwarding the viewer's session cookie. This fallback has been removed.
- **Sprint details by id.** Any logged-in user could read the name, goal and dates of any sprint by its id, regardless of board permissions.
