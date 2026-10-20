package com.garfieldArlene.jira.gadget.rest;

import com.atlassian.jira.bc.issue.search.SearchService;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.link.IssueLink;
import com.atlassian.jira.issue.link.IssueLinkManager;
import com.atlassian.jira.issue.search.SearchResults;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.web.bean.PagerFilter;
import com.google.gson.*;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Path("")
public class SprintGadgetResource {

    private static final String STORY_POINTS_CF_NAME = "Story Points";
    private static final String STORY_POINTS_CF_ALT = "Story Point Estimate";

    private static final java.util.logging.Logger LOG =
        java.util.logging.Logger.getLogger("com.garfieldArlene.sprintgadget");

    @GET
    @Path("/data")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSprintData(@Context HttpServletRequest request,
                                   @QueryParam("sprintId") Long sprintId,
                                   @QueryParam("boardId") Long boardId) {
        try {
            JsonObject result = new JsonObject();

            if (sprintId == null && boardId == null) {
                result.add("sprints", getAvailableSprints(boardId));
                return Response.ok(result.toString()).build();
            }

            Object sprint = getSprint(sprintId, boardId);
            if (sprint == null) {
                return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"Sprint not found\"}").build();
            }

            result.add("sprint", buildSprintJson(sprint));
            result.add("health", buildHealthJson(sprint, request));
            result.add("issuesByStatus", buildIssuesByStatusJson(sprint));

            return Response.ok(result.toString()).build();

        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty("error", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(error.toString()).build();
        }
    }

    @GET
    @Path("/sprints")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSprints(@Context HttpServletRequest request,
                                @QueryParam("boardId") Long boardId) {
        JsonArray sprints = getAvailableSprints(boardId);
        JsonObject result = new JsonObject();
        result.add("sprints", sprints);
        return Response.ok(result.toString()).build();
    }

    private Object getSprint(Long sprintId, Long boardId) {
        List<Object> sprints = findSprints(sprintId, boardId);
        return sprints.isEmpty() ? null : sprints.get(0);
    }

    private JsonArray getAvailableSprints(Long boardId) {
        JsonArray array = new JsonArray();
        for (Object s : findSprints(null, boardId)) {
            JsonObject json = new JsonObject();
            json.addProperty("id", invokeLong(s, "getId"));
            json.addProperty("name", invokeString(s, "getName"));
            json.addProperty("state", invokeEnumName(s, "getState"));
            json.addProperty("startDate", invokeDateTime(s, "getStartDate"));
            json.addProperty("endDate", invokeDateTime(s, "getEndDate"));
            json.addProperty("goal", invokeString(s, "getGoal"));
            array.add(json);
        }
        return array;
    }

    /**
     * Enumerates sprints by reading the values of the greenhopper Sprint custom
     * field on issues the current user can see. SprintManager is not exported as
     * an OSGi service, so it cannot be looked up from another plugin.
     */
    private List<Object> findSprints(Long sprintId, Long boardId) {
        Map<Long, Object> byId = new TreeMap<>();
        try {
            JiraAuthenticationContext authContext = ComponentAccessor.getComponent(JiraAuthenticationContext.class);
            ApplicationUser user = authContext.getLoggedInUser();
            if (user == null) return new ArrayList<>();

            CustomField sprintField = getSprintCustomField();
            if (sprintField == null) return new ArrayList<>();

            SearchService searchService = ComponentAccessor.getComponent(SearchService.class);
            SearchService.ParseResult parsed = searchService.parseQuery(user, "sprint is not EMPTY");
            if (!parsed.isValid()) return new ArrayList<>();

            SearchResults<Issue> results = searchService.search(user, parsed.getQuery(), PagerFilter.getUnlimitedFilter());
            for (Issue issue : results.getResults()) {
                Object value = issue.getCustomFieldValue(sprintField);
                if (!(value instanceof Collection)) continue;
                for (Object s : (Collection<?>) value) {
                    Long id = invokeLong(s, "getId");
                    if (id == null || id == 0L) continue;
                    if (sprintId != null && !sprintId.equals(id)) continue;
                    if (boardId != null && !boardId.equals(invokeLong(s, "getRapidViewId"))) continue;
                    byId.putIfAbsent(id, s);
                }
            }
        } catch (Exception e) {
            LOG.log(java.util.logging.Level.WARNING, "findSprints failed", e);
        }
        return new ArrayList<>(byId.values());
    }

    private CustomField getSprintCustomField() {
        for (CustomField cf : ComponentAccessor.getCustomFieldManager().getCustomFieldObjects()) {
            if ("com.pyxis.greenhopper.jira:gh-sprint".equals(cf.getCustomFieldType().getKey())) {
                return cf;
            }
        }
        return null;
    }

    private JsonObject buildSprintJson(Object sprint) {
        JsonObject json = new JsonObject();
        json.addProperty("id", invokeLong(sprint, "getId"));
        json.addProperty("name", invokeString(sprint, "getName"));
        json.addProperty("state", invokeEnumName(sprint, "getState"));
        json.addProperty("goal", invokeString(sprint, "getGoal"));
        json.addProperty("startDate", invokeDateTime(sprint, "getStartDate"));
        json.addProperty("endDate", invokeDateTime(sprint, "getEndDate"));
        json.addProperty("completeDate", invokeDateTime(sprint, "getCompleteDate"));
        json.addProperty("activatedDate", invokeDateTime(sprint, "getActivatedDate"));
        return json;
    }

    private JsonObject buildHealthJson(Object sprint, HttpServletRequest request) {
        return calculateHealthFallback(sprint, request);
    }

    private JsonObject calculateHealthFallback(Object sprint, HttpServletRequest request) {
        JsonObject health = new JsonObject();
        List<Issue> issues = getSprintIssues(sprint);
        
        CustomField storyPointsField = getStoryPointsField();
        double committed = 0, completed = 0, added = 0, removed = 0;
        int totalIssues = issues.size();
        int completedIssues = 0;
        int addedIssues = 0;
        int removedIssues = 0;
        long sprintId = invokeLong(sprint, "getId");
        long rapidViewId = invokeLong(sprint, "getRapidViewId");
        
        Map<String, Double> spByKey = new HashMap<>();
        for (Issue issue : issues) {
            Double sp = getStoryPoints(issue, storyPointsField);
            if (sp != null) {
                spByKey.put(issue.getKey(), sp);
                committed += sp;
                if (isDone(issue)) {
                    completed += sp;
                    completedIssues++;
                }
            } else if (isDone(issue)) {
                completedIssues++;
            }
        }
        
        // Pull authoritative scope change from GreenHopper Sprint Report
        JsonObject report = fetchSprintReport(rapidViewId, sprintId, request);
        if (report != null && report.has("contents") && report.get("contents").isJsonObject()) {
            JsonObject contents = report.getAsJsonObject("contents");
            if (contents.has("issueKeysAddedDuringSprint") && contents.get("issueKeysAddedDuringSprint").isJsonObject()) {
                JsonObject addedKeys = contents.getAsJsonObject("issueKeysAddedDuringSprint");
                addedIssues = addedKeys.entrySet().size();
                for (Map.Entry<String, JsonElement> e : addedKeys.entrySet()) {
                    Double sp = spByKey.get(e.getKey());
                    if (sp != null) added += sp;
                }
            }
            removed = sumEstimate(contents.has("puntedIssuesEstimateSum") ? contents.get("puntedIssuesEstimateSum") : null);
            if (contents.has("puntedIssues") && contents.get("puntedIssues").isJsonArray()) {
                JsonArray punted = contents.getAsJsonArray("puntedIssues");
                removedIssues = punted.size();
                if (removed <= 0) {
                    for (JsonElement p : punted) {
                        if (p.isJsonObject() && p.getAsJsonObject().has("estimateStatistic")) {
                            removed += sumEstimate(p.getAsJsonObject().getAsJsonObject("estimateStatistic").get("statFieldValue"));
                        }
                    }
                }
            }
        }
        
        // Original commitment = what is in the sprint now, minus what was added later,
        // plus what was removed from the sprint.
        committed = Math.max(0, committed - added + removed);
        
        health.addProperty("committedStoryPoints", committed);
        health.addProperty("completedStoryPoints", completed);
        health.addProperty("addedStoryPoints", added);
        health.addProperty("removedStoryPoints", removed);
        health.addProperty("totalIssues", totalIssues);
        health.addProperty("completedIssues", completedIssues);
        health.addProperty("addedIssues", addedIssues);
        health.addProperty("removedIssues", removedIssues);
        health.addProperty("completionPercent", committed > 0 ? Math.round((completed / committed) * 100) : 0);
        health.addProperty("issueCompletionPercent", totalIssues > 0 ? Math.round((completedIssues / (double) totalIssues) * 100) : 0);
        
        return health;
    }

    private JsonObject fetchSprintReport(long rapidViewId, long sprintId, HttpServletRequest request) {
        if (rapidViewId == 0 || sprintId == 0) return null;
        try {
            String baseUrl = null;
            if (request != null) {
                int port = request.getServerPort();
                boolean defaultPort = ("http".equals(request.getScheme()) && port == 80)
                    || ("https".equals(request.getScheme()) && port == 443);
                baseUrl = request.getScheme() + "://" + request.getServerName()
                    + (defaultPort ? "" : ":" + port) + request.getContextPath();
            }
            if (baseUrl == null) {
                baseUrl = ComponentAccessor.getApplicationProperties().getString(com.atlassian.jira.config.properties.APKeys.JIRA_BASEURL);
            }
            if (baseUrl == null) baseUrl = "http://localhost:2990/jira";
            baseUrl = baseUrl.replaceAll("/$", "");
            URL url = new URL(baseUrl + "/rest/greenhopper/1.0/rapid/charts/sprintreport?rapidViewId=" + rapidViewId + "&sprintId=" + sprintId);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            if (request != null) {
                String cookie = request.getHeader("Cookie");
                if (cookie != null) conn.setRequestProperty("Cookie", cookie);
                String authorization = request.getHeader("Authorization");
                if (authorization != null) conn.setRequestProperty("Authorization", authorization);
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                LOG.log(java.util.logging.Level.WARNING, "fetchSprintReport HTTP " + code + " for " + url);
                return null;
            }
            java.io.InputStream is = conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return new JsonParser().parse(sb.toString()).getAsJsonObject();
        } catch (Exception e) {
            LOG.log(java.util.logging.Level.WARNING, "fetchSprintReport failed", e);
            return null;
        }
    }

    private double sumEstimate(JsonElement el) {
        if (el == null || el.isJsonNull()) return 0;
        if (el.isJsonPrimitive()) {
            try { return el.getAsDouble(); } catch (Exception ex) { return 0; }
        }
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            if (o.has("value")) {
                try { return o.get("value").isJsonNull() ? 0 : o.get("value").getAsDouble(); } catch (Exception ex) { return 0; }
            }
        }
        return 0;
    }

    private JsonObject buildIssuesByStatusJson(Object sprint) {
        JsonObject result = new JsonObject();
        List<Issue> issues = getSprintIssues(sprint);
        CustomField storyPointsField = getStoryPointsField();

        Map<String, List<JsonObject>> grouped = new LinkedHashMap<>();
        for (Issue issue : issues) {
            String status = issue.getStatus().getName();
            grouped.computeIfAbsent(status, k -> new ArrayList<>()).add(buildIssueJson(issue, storyPointsField));
        }

        // Sort statuses: To Do -> In Progress -> In Review -> Done
        List<String> statusOrder = Arrays.asList("To Do", "Open", "In Progress", "In Review", "Code Review", "Testing", "Done", "Closed", "Resolved");
        
        JsonObject sorted = new JsonObject();
        for (String status : statusOrder) {
            if (grouped.containsKey(status)) {
                JsonArray arr = new JsonArray();
                for (JsonObject obj : grouped.get(status)) arr.add(obj);
                sorted.add(status, arr);
            }
        }
        // Add any remaining statuses not in order
        for (String status : grouped.keySet()) {
            if (!statusOrder.contains(status)) {
                JsonArray arr = new JsonArray();
                for (JsonObject obj : grouped.get(status)) arr.add(obj);
                sorted.add(status, arr);
            }
        }

        return sorted;
    }

    private List<Issue> getSprintIssues(Object sprint) {
        try {
            Long sprintId = invokeLong(sprint, "getId");
            if (sprintId == null || sprintId == 0L) return Collections.emptyList();

            JiraAuthenticationContext authContext = ComponentAccessor.getComponent(JiraAuthenticationContext.class);
            ApplicationUser user = authContext.getLoggedInUser();
            if (user == null) return Collections.emptyList();

            SearchService searchService = ComponentAccessor.getComponent(SearchService.class);
            SearchService.ParseResult parsed = searchService.parseQuery(user, "sprint = " + sprintId);
            if (!parsed.isValid()) return Collections.emptyList();

            SearchResults<Issue> results = searchService.search(user, parsed.getQuery(), PagerFilter.getUnlimitedFilter());
            return new ArrayList<>(results.getResults());
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private JsonObject buildIssueJson(Issue issue, CustomField storyPointsField) {
        JsonObject json = new JsonObject();
        json.addProperty("key", issue.getKey());
        json.addProperty("summary", issue.getSummary());
        json.addProperty("status", issue.getStatus().getName());
        json.addProperty("statusCategory", issue.getStatus().getStatusCategory().getName());
        json.addProperty("issueType", issue.getIssueType().getName());
        json.addProperty("assignee", issue.getAssignee() != null ? issue.getAssignee().getDisplayName() : "Unassigned");
        json.addProperty("priority", issue.getPriority() != null ? issue.getPriority().getName() : "None");
        
        Double sp = getStoryPoints(issue, storyPointsField);
        json.addProperty("storyPoints", sp != null ? sp : 0);
        
        // Blocked status (issue links)
        boolean blocked = isBlocked(issue);
        json.addProperty("blocked", blocked);
        if (blocked) {
            json.addProperty("blockedReason", getBlockedReason(issue));
        }
        
        // Flagged status (Jira's built-in flag/impediment)
        boolean flagged = isFlagged(issue);
        json.addProperty("flagged", flagged);
        if (flagged) {
            json.addProperty("flaggedReason", getFlaggedReason(issue));
        }
        
        return json;
    }

    private boolean isFlagged(Issue issue) {
        CustomFieldManager cfManager = ComponentAccessor.getCustomFieldManager();
        for (CustomField cf : cfManager.getCustomFieldObjects()) {
            String name = cf.getName().toLowerCase();
            if (name.equals("flagged") || name.equals("impediment flag")) {
                Object value = issue.getCustomFieldValue(cf);
                if (value != null && (value instanceof Boolean ? (Boolean) value : !value.toString().isEmpty())) {
                    return true;
                }
            }
        }
        return hasFlagLabel(issue);
    }

    private String getFlaggedReason(Issue issue) {
        for (String label : getLabelNames(issue)) {
            if (isFlagLabel(label)) {
                return "Flagged: " + label;
            }
        }
        return "Flagged as impediment";
    }

    private boolean isBlocked(Issue issue) {
        try {
            IssueLinkManager issueLinkManager = ComponentAccessor.getComponent(IssueLinkManager.class);
            List<IssueLink> links = new ArrayList<>(issueLinkManager.getOutwardLinks(issue.getId()));
            links.addAll(issueLinkManager.getInwardLinks(issue.getId()));
            for (IssueLink link : links) {
                if (isBlockLink(link)) {
                    return true;
                }
            }
        } catch (Exception e) {
            LOG.log(java.util.logging.Level.WARNING, "isBlocked failed for " + issue.getKey(), e);
        }

        CustomFieldManager cfManager = ComponentAccessor.getCustomFieldManager();
        for (CustomField cf : cfManager.getCustomFieldObjects()) {
            String name = cf.getName().toLowerCase();
            if (name.contains("flag") || name.contains("impediment") || name.contains("blocked")) {
                Object value = issue.getCustomFieldValue(cf);
                if (value != null && (value instanceof Boolean ? (Boolean) value : !value.toString().isEmpty())) {
                    return true;
                }
            }
        }
        return hasFlagLabel(issue);
    }

    private String getBlockedReason(Issue issue) {
        try {
            IssueLinkManager issueLinkManager = ComponentAccessor.getComponent(IssueLinkManager.class);
            for (IssueLink link : issueLinkManager.getInwardLinks(issue.getId())) {
                if (isBlockLink(link)) {
                    return "Blocked by " + link.getSourceObject().getKey() + ": " + link.getSourceObject().getSummary();
                }
            }
            for (IssueLink link : issueLinkManager.getOutwardLinks(issue.getId())) {
                if (isBlockLink(link)) {
                    return "Blocks " + link.getDestinationObject().getKey() + ": " + link.getDestinationObject().getSummary();
                }
            }
        } catch (Exception e) {
            LOG.log(java.util.logging.Level.WARNING, "getBlockedReason failed for " + issue.getKey(), e);
        }
        return "Blocked";
    }

    private boolean isBlockLink(IssueLink link) {
        String linkType = link.getIssueLinkType().getName().toLowerCase();
        return linkType.contains("block") || linkType.contains("imped");
    }

    private boolean hasFlagLabel(Issue issue) {
        for (String label : getLabelNames(issue)) {
            if (isFlagLabel(label)) {
                return true;
            }
        }
        return false;
    }

    private List<String> getLabelNames(Issue issue) {
        List<String> names = new ArrayList<>();
        for (com.atlassian.jira.issue.label.Label label : issue.getLabels()) {
            names.add(label.getLabel());
        }
        return names;
    }

    private boolean isFlagLabel(String label) {
        String l = label.toLowerCase();
        return l.equals("flagged") || l.equals("impediment");
    }

    private CustomField getStoryPointsField() {
        CustomFieldManager cfManager = ComponentAccessor.getCustomFieldManager();
        List<CustomField> fields = cfManager.getCustomFieldObjects();
        
        for (CustomField cf : fields) {
            String name = cf.getName();
            if (STORY_POINTS_CF_NAME.equalsIgnoreCase(name) || STORY_POINTS_CF_ALT.equalsIgnoreCase(name)) {
                return cf;
            }
        }
        return fields.stream()
            .filter(cf -> "com.atlassian.jira.plugin.system.customfieldtypes:float".equals(cf.getCustomFieldType().getKey()))
            .findFirst().orElse(null);
    }

    private Double getStoryPoints(Issue issue, CustomField field) {
        if (field == null) return null;
        Object value = issue.getCustomFieldValue(field);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return null;
    }

    private boolean isDone(Issue issue) {
        return issue.getStatus().getStatusCategory().getKey().equals("done");
    }

    // Reflection helpers
    private Long invokeLong(Object obj, String methodName) {
        try {
            Method m = obj.getClass().getMethod(methodName);
            Object result = m.invoke(obj);
            return result != null ? ((Number) result).longValue() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private String invokeString(Object obj, String methodName) {
        try {
            Method m = obj.getClass().getMethod(methodName);
            Object result = m.invoke(obj);
            return result != null ? result.toString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String invokeEnumName(Object obj, String methodName) {
        try {
            Method m = obj.getClass().getMethod(methodName);
            Object result = m.invoke(obj);
            return result != null ? result.getClass().getMethod("name").invoke(result).toString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private Long invokeDateTime(Object obj, String methodName) {
        try {
            Method m = obj.getClass().getMethod(methodName);
            Object result = m.invoke(obj);
            if (result != null) {
                try {
                    return (Long) result.getClass().getMethod("getMillis").invoke(result);
                } catch (NoSuchMethodException e) {
                    return (Long) result.getClass().getMethod("getTime").invoke(result);
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return 0L;
    }
}