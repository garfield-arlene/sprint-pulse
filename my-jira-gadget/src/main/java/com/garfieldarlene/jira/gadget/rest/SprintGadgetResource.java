package com.garfieldarlene.jira.gadget.rest;

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
import com.atlassian.plugin.Plugin;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import java.util.Arrays;

/**
 * REST resource providing sprint health data and issue details for the Sprint Pulse gadget.
 * Endpoints:
 * <ul>
 *   <li>GET /rest/sprint/1.0/data - sprint health, issues by status</li>
 *   <li>GET /rest/sprint/1.0/sprints - available sprints for a board</li>
 * </ul>
 */
@Path("")
public class SprintGadgetResource {

  private static final String STORY_POINTS_CF_NAME = "Story Points";
  private static final String STORY_POINTS_CF_ALT = "Story Point Estimate";

  /** GreenHopper plugin whose sprint report is reused, and types that live in its bundle. */
  private static final String GREENHOPPER_PLUGIN_KEY = "com.pyxis.greenhopper.jira";
  private static final String GREENHOPPER_HISTORIC_SPRINT_DATA_FACTORY =
      "com.atlassian.greenhopper.web.rapid.chart.HistoricSprintDataFactory";
  private static final String GREENHOPPER_RAPID_VIEW_SERVICE =
      "com.atlassian.greenhopper.service.rapid.view.RapidViewService";
  private static final String GREENHOPPER_SPRINT_MANAGER =
      "com.atlassian.greenhopper.service.sprint.SprintManager";
  private static final String GREENHOPPER_MARKER_CLASS = GREENHOPPER_HISTORIC_SPRINT_DATA_FACTORY;

  /** OSGi service under which the spring extender registers each plugin's application context. */
  private static final String SPRING_CONTEXT_SERVICE = "org.springframework.context.ApplicationContext";
  private static final String SPRING_CONTEXT_NAME_PROPERTY = "org.springframework.context.service.name";

  /** Sprint list ordering: the running sprint first, then sprints still to come, then history. */
  private static final List<String> SPRINT_STATE_ORDER = Arrays.asList("ACTIVE", "FUTURE", "CLOSED");

  /** How much closed sprint history the list keeps; older sprints only bloat the dropdown. */
  private static final int MAX_CLOSED_SPRINTS = 20;

  /**
   * The sprint report's issue lists. Every issue scoped to the sprint appears in exactly one of
   * them, so together they also cover an issue that was added and later removed.
   */
  private static final String[] SPRINT_REPORT_ISSUE_LISTS = {
      "completedIssues",
      "issuesNotCompletedInCurrentSprint",
      "puntedIssues",
      "issuesCompletedInAnotherSprint"
  };

  /**
   * How long a fetched greenhopper sprint report may be reused.
   *
   * <p>The report is the only source of the scope change figures (issues added to and removed
   * from the sprint), so a cached copy that never expires freezes those numbers for the life
   * of the plugin: a story added to or moved out of the sprint keeps the old counts. Every
   * cached entry therefore carries a timestamp and is discarded once it is older than this
   * TTL, which bounds how stale the gadget can ever show data.
   */
  private static final long REPORT_CACHE_TTL_MILLIS = 30_000L;

  /** Upper bound on cached reports, so many sprints cannot grow the cache without limit. */
  private static final int REPORT_CACHE_MAX_ENTRIES = 20;

  private static final java.util.concurrent.ConcurrentMap<String, CachedSprintReport> REPORT_CACHE =
      new java.util.concurrent.ConcurrentHashMap<>();

  private static final class CachedSprintReport {
    private final JsonObject report;
    private final long fetchedAt;

    CachedSprintReport(JsonObject report) {
      this.report = report;
      this.fetchedAt = System.currentTimeMillis();
    }

    boolean isExpired() {
      return System.currentTimeMillis() - fetchedAt >= REPORT_CACHE_TTL_MILLIS;
    }
  }

  private static final java.util.logging.Logger LOG =
      java.util.logging.Logger.getLogger("com.garfieldarlene.sprintgadget");

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

    @GET
    @Path("/boards")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getBoards() {
        JsonObject result = new JsonObject();
        result.add("boards", getAvailableBoards());
        return Response.ok(result.toString()).build();
    }

    /**
     * Boards the current user may see that support sprints, sorted by name. Used by the gadget's
     * board selector; an empty list means the filter is not available and the gadget lists
     * sprints from every board.
     */
    private JsonArray getAvailableBoards() {
        JsonArray array = new JsonArray();
        for (Object board : findBoards()) {
            JsonObject json = new JsonObject();
            json.addProperty("id", invokeLong(board, "getId"));
            json.addProperty("name", invokeString(board, "getName"));
            array.add(json);
        }
        return array;
    }

    private List<Object> findBoards() {
        List<Object> boards = new ArrayList<>();
        try {
            ApplicationUser user = ComponentAccessor.getComponent(JiraAuthenticationContext.class).getLoggedInUser();
            if (user == null) return boards;

            Object rapidViewService = greenHopperBeans(GREENHOPPER_RAPID_VIEW_SERVICE)
                .get(GREENHOPPER_RAPID_VIEW_SERVICE);
            if (rapidViewService == null) return boards;

            Object visible = outcomeValue(invokeMethod(rapidViewService, "getRapidViews", user));
            if (!(visible instanceof Collection)) return boards;

            for (Object board : (Collection<?>) visible) {
                if (invokeLong(board, "getId") == 0L) continue;
                if (!sprintSupportEnabled(board)) continue;
                boards.add(board);
            }
            boards.sort((left, right) ->
                invokeString(left, "getName").compareToIgnoreCase(invokeString(right, "getName")));
        } catch (Exception e) {
            LOG.log(java.util.logging.Level.WARNING, "Could not list boards through greenhopper", e);
        }
        return boards;
    }

    /** Kanban and other non-sprint boards are skipped when their type can be determined. */
    private boolean sprintSupportEnabled(Object board) {
        try {
            Object enabled = invokeMethod(board, "isSprintSupportEnabled");
            return !(enabled instanceof Boolean) || (Boolean) enabled;
        } catch (Exception e) {
            return true;
        }
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
     * Lists sprints through greenhopper's own services, which includes sprints that have no
     * issues yet and scopes the list to boards the user may see. Falls back to reading the
     * Sprint custom field off issues if the services cannot be resolved. List results are
     * ordered for the dropdown and trimmed to the recent closed sprints; single lookups are not.
     */
    private List<Object> findSprints(Long sprintId, Long boardId) {
        List<Object> sprints = findSprintsFromServices(sprintId, boardId);
        if (sprints == null) sprints = findSprintsFromIssues(sprintId, boardId);
        if (sprintId != null) return sprints;
        sprints.sort(this::compareSprints);
        return limitClosedSprints(sprints);
    }

    /**
     * Resolves the sprint list through RapidViewService and SprintManager. Returns {@code null}
     * when the greenhopper services are unavailable so the caller can fall back.
     */
    private List<Object> findSprintsFromServices(Long sprintId, Long boardId) {
        try {
            ApplicationUser user = ComponentAccessor.getComponent(JiraAuthenticationContext.class).getLoggedInUser();
            if (user == null) return null;

            Map<String, Object> beans = greenHopperBeans(
                GREENHOPPER_RAPID_VIEW_SERVICE, GREENHOPPER_SPRINT_MANAGER);
            Object rapidViewService = beans.get(GREENHOPPER_RAPID_VIEW_SERVICE);
            Object sprintManager = beans.get(GREENHOPPER_SPRINT_MANAGER);
            if (rapidViewService == null || sprintManager == null) return null;

            List<Object> sprints = new ArrayList<>();
            if (sprintId != null) {
                // Look the sprint up directly so a sprint without issues still resolves. Board
                // visibility is not required here: the issues are filtered by the user's search
                // permissions anyway, exactly as before.
                Object sprint = outcomeValue(invokeMethod(sprintManager, "getSprint", sprintId));
                if (sprint != null && (boardId == null || boardId.equals(invokeLong(sprint, "getRapidViewId")))) {
                    sprints.add(sprint);
                }
                return sprints;
            }

            List<Object> rapidViews = new ArrayList<>();
            if (boardId != null) {
                Object rapidView = outcomeValue(invokeMethod(rapidViewService, "getRapidView", user, boardId));
                if (rapidView != null) rapidViews.add(rapidView);
            } else {
                Object visible = outcomeValue(invokeMethod(rapidViewService, "getRapidViews", user));
                if (visible instanceof Collection) rapidViews.addAll((Collection<?>) visible);
            }

            Map<Long, Object> byId = new LinkedHashMap<>();
            for (Object rapidView : rapidViews) {
                Object viewSprints = outcomeValue(invokeMethod(sprintManager, "getSprintsForView", rapidView));
                if (!(viewSprints instanceof Collection)) continue;
                for (Object sprint : (Collection<?>) viewSprints) {
                    Long id = invokeLong(sprint, "getId");
                    if (id == null || id == 0L) continue;
                    byId.putIfAbsent(id, sprint);
                }
            }
            sprints.addAll(byId.values());
            return sprints;
        } catch (Exception e) {
            LOG.log(java.util.logging.Level.WARNING,
                "Could not list sprints through greenhopper, falling back to issue values", e);
            return null;
        }
    }

    /**
     * Older fallback that reads the values of the greenhopper Sprint custom field on issues the
     * current user can see. It misses sprints without issues, so it only runs when the services
     * are not available.
     */
    private List<Object> findSprintsFromIssues(Long sprintId, Long boardId) {
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
            LOG.log(java.util.logging.Level.WARNING, "findSprintsFromIssues failed", e);
        }
        return new ArrayList<>(byId.values());
    }

    /**
     * Running sprint first, then future ones in planned start order, then closed ones newest
     * first, so the list opens on the current sprint and the retained history is the recent one.
     * Sequence numbers are per board, so they cannot order sprints from several boards.
     */
    private int compareSprints(Object left, Object right) {
        int byState = Integer.compare(sprintStateRank(left), sprintStateRank(right));
        if (byState != 0) return byState;
        if ("CLOSED".equals(invokeEnumName(left, "getState"))) {
            return Long.compare(sprintEndTime(right), sprintEndTime(left));
        }
        return Long.compare(sprintStartTime(left), sprintStartTime(right));
    }

    private int sprintStateRank(Object sprint) {
        int index = SPRINT_STATE_ORDER.indexOf(invokeEnumName(sprint, "getState"));
        return index < 0 ? SPRINT_STATE_ORDER.size() : index;
    }

    /**
     * Keeps only the most recent closed sprints. Discovery returns the whole history of every
     * visible board, which on a long-lived instance would fill the dropdown with old sprints.
     * The list must already be sorted newest first.
     */
    private List<Object> limitClosedSprints(List<Object> sprints) {
        List<Object> limited = new ArrayList<>();
        int closed = 0;
        for (Object sprint : sprints) {
            if ("CLOSED".equals(invokeEnumName(sprint, "getState"))
                && ++closed > MAX_CLOSED_SPRINTS) {
                continue;
            }
            limited.add(sprint);
        }
        return limited;
    }

    /** Start time in millis, or the end of time so undated future sprints sort last. */
    private long sprintStartTime(Object sprint) {
        long start = invokeDateTime(sprint, "getStartDate");
        return start != 0L ? start : Long.MAX_VALUE;
    }

    /** End time in millis, falling back to the completion time for sprints closed without one. */
    private long sprintEndTime(Object sprint) {
        long end = invokeDateTime(sprint, "getEndDate");
        return end != 0L ? end : invokeDateTime(sprint, "getCompleteDate");
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
                Map<String, Double> reportPoints = reportEstimates(contents);
                addedIssues = addedKeys.entrySet().size();
                for (String key : addedKeys.keySet()) {
                    // The report covers issues that were added and later removed; the current
                    // sprint does not, so prefer the report and fall back to the sprint issues.
                    Double sp = reportPoints.containsKey(key) ? reportPoints.get(key) : spByKey.get(key);
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
        String cacheKey = rapidViewId + ":" + sprintId;
        CachedSprintReport cached = REPORT_CACHE.get(cacheKey);
        if (cached != null) {
            if (!cached.isExpired()) {
                return cached.report;
            }
            REPORT_CACHE.remove(cacheKey, cached);
        }
        JsonObject report = fetchSprintReportInProcess(rapidViewId, sprintId);
        if (report == null) {
            report = fetchSprintReportOverHttp(rapidViewId, sprintId, request);
        }
        if (report != null) {
            REPORT_CACHE.put(cacheKey, new CachedSprintReport(report));
            evictExpiredReports();
        }
        return report;
    }

    /**
     * Builds the sprint report by calling GreenHopper's own service inside this JVM.
     *
     * <p>The greenhopper plugin does not publish its components as OSGi services, but its
     * bundle exports the classes and its Spring application context is registered in the
     * OSGi service registry (property {@code org.springframework.context.service.name}).
     * Looking the context up lets the report be produced without a loopback HTTP request,
     * which used to block for up to three seconds on the very thread rendering the gadget.
     *
     * <p>Returns {@code null} when the beans cannot be resolved or the user may not see the
     * board, in which case the caller falls back to the REST endpoint.
     */
    private JsonObject fetchSprintReportInProcess(long rapidViewId, long sprintId) {
        try {
            ApplicationUser user = ComponentAccessor.getComponent(JiraAuthenticationContext.class).getLoggedInUser();
            if (user == null) return null;

            Map<String, Object> beans = greenHopperBeans(
                GREENHOPPER_HISTORIC_SPRINT_DATA_FACTORY,
                GREENHOPPER_RAPID_VIEW_SERVICE,
                GREENHOPPER_SPRINT_MANAGER);
            Object dataFactory = beans.get(GREENHOPPER_HISTORIC_SPRINT_DATA_FACTORY);
            Object rapidViewService = beans.get(GREENHOPPER_RAPID_VIEW_SERVICE);
            Object sprintManager = beans.get(GREENHOPPER_SPRINT_MANAGER);
            if (dataFactory == null || rapidViewService == null || sprintManager == null) {
                return null;
            }

            Object rapidView = outcomeValue(invokeMethod(rapidViewService, "getRapidView", user, rapidViewId));
            Object sprint = outcomeValue(invokeMethod(sprintManager, "getSprint", sprintId));
            if (rapidView == null || sprint == null) {
                LOG.log(java.util.logging.Level.WARNING,
                    "In-process sprint report: board " + rapidViewId + " or sprint " + sprintId + " not visible");
                return null;
            }

            Object data = outcomeValue(invokeMethod(dataFactory, "getSprintOriginalContents", user, rapidView, sprint));
            JsonObject report = toSprintReportJson(data);
            if (report != null) {
                LOG.log(java.util.logging.Level.FINE,
                    "Built sprint report for board " + rapidViewId + " sprint " + sprintId + " in process");
            }
            return report;
        } catch (Exception e) {
            LOG.log(java.util.logging.Level.WARNING,
                "In-process sprint report failed, falling back to the REST endpoint", e);
            return null;
        }
    }

    /**
     * Resolves beans from the Spring context of the greenhopper bundle, keyed by type name.
     *
     * <p>The bundle exports {@code com.atlassian.greenhopper.*} but none of its components are
     * OSGi services, so the only way in is the application context the extender registers for
     * the bundle. Types are named rather than imported because they live in greenhopper's
     * class loader, where this plugin cannot cast to them. Looking up by type also avoids
     * depending on bean names, which are an implementation detail.
     */
    private Map<String, Object> greenHopperBeans(String... typeNames) {
        Map<String, Object> beans = new HashMap<>();
        Plugin plugin = ComponentAccessor.getPluginAccessor().getPlugin(GREENHOPPER_PLUGIN_KEY);
        if (plugin == null) {
            LOG.log(java.util.logging.Level.WARNING,
                "GreenHopper plugin " + GREENHOPPER_PLUGIN_KEY + " is not enabled, falling back to the REST endpoint");
            return beans;
        }
        try {
            ClassLoader loader = plugin.getClassLoader();
            Bundle bundle = FrameworkUtil.getBundle(loader.loadClass(GREENHOPPER_MARKER_CLASS));
            BundleContext bundleContext = bundle != null ? bundle.getBundleContext() : null;
            if (bundleContext == null) return beans;

            ServiceReference<?>[] references =
                bundleContext.getServiceReferences(SPRING_CONTEXT_SERVICE, null);
            if (references == null) return beans;
            for (ServiceReference<?> reference : references) {
                if (!bundle.getSymbolicName().equals(reference.getProperty(SPRING_CONTEXT_NAME_PROPERTY))) {
                    continue;
                }
                Object springContext = bundleContext.getService(reference);
                if (springContext == null) continue;
                try {
                    for (String typeName : typeNames) {
                        Object bean = beanOfType(springContext, loader.loadClass(typeName));
                        if (bean != null) {
                            beans.put(typeName, bean);
                        }
                    }
                } finally {
                    bundleContext.ungetService(reference);
                }
                return beans;
            }
            LOG.log(java.util.logging.Level.WARNING,
                "No Spring application context service found for bundle " + bundle.getSymbolicName());
        } catch (Exception e) {
            LOG.log(java.util.logging.Level.WARNING,
                "Could not resolve greenhopper beans " + Arrays.toString(typeNames), e);
        }
        return beans;
    }

    /** Returns the first bean of the given type from the greenhopper Spring context. */
    private Object beanOfType(Object springContext, Class<?> type) throws ReflectiveOperationException {
        Object names = invokeMethod(springContext, "getBeanNamesForType", type);
        if (names instanceof String[] && ((String[]) names).length > 0) {
            return invokeMethod(springContext, "getBean", ((String[]) names)[0]);
        }
        return null;
    }

    /**
     * Maps greenhopper's {@code HistoricSprintData} to the subset of the sprint report JSON the
     * gadget reads. Only the scope change figures are needed; issues and estimates come from
     * JQL, so this is not a byte for byte copy of the REST response.
     */
    private JsonObject toSprintReportJson(Object data) {
        Object contents = fieldValue(data, "contents");
        if (contents == null) return null;

        JsonObject contentsJson = new JsonObject();

        Object added = fieldValue(contents, "issueKeysAddedDuringSprint");
        if (added instanceof Map) {
            JsonObject addedJson = new JsonObject();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) added).entrySet()) {
                addedJson.addProperty(String.valueOf(entry.getKey()), Boolean.TRUE.equals(entry.getValue()));
            }
            contentsJson.add("issueKeysAddedDuringSprint", addedJson);
        }

        for (String issueList : SPRINT_REPORT_ISSUE_LISTS) {
            contentsJson.add(issueList, issueListJson(contents, issueList));
        }

        JsonObject puntedEstimateSum = numberValue(fieldValue(contents, "puntedIssuesEstimateSum"));
        if (puntedEstimateSum != null) {
            contentsJson.add("puntedIssuesEstimateSum", puntedEstimateSum);
        }

        JsonObject report = new JsonObject();
        report.add("contents", contentsJson);
        return report;
    }

    /**
     * Maps one of the report's issue lists to JSON with the key and estimate, which is all the
     * health calculation needs. The report groups every scoped issue into exactly one of these
     * lists, so together they cover issues that were added and later removed as well.
     */
    private JsonArray issueListJson(Object contents, String fieldName) {
        JsonArray issuesJson = new JsonArray();
        Object issues = fieldValue(contents, fieldName);
        if (!(issues instanceof Collection)) return issuesJson;
        for (Object issue : (Collection<?>) issues) {
            JsonObject issueJson = new JsonObject();
            Object key = fieldValue(issue, "key");
            if (key != null) {
                issueJson.addProperty("key", key.toString());
            }
            JsonObject estimateStatistic = new JsonObject();
            JsonObject estimate = numberValue(fieldValue(fieldValue(issue, "estimateStatistic"), "statFieldValue"));
            if (estimate != null) {
                estimateStatistic.add("statFieldValue", estimate);
            }
            issueJson.add("estimateStatistic", estimateStatistic);
            issuesJson.add(issueJson);
        }
        return issuesJson;
    }

    /** Mirrors greenhopper's {@code NumberFieldValue} ({value, text}) which the report reader sums. */
    private JsonObject numberValue(Object numberFieldValue) {
        Object value = fieldValue(numberFieldValue, "value");
        if (!(value instanceof Number)) return null;
        JsonObject json = new JsonObject();
        json.addProperty("value", (Number) value);
        Object text = fieldValue(numberFieldValue, "text");
        if (text != null) {
            json.addProperty("text", text.toString());
        }
        return json;
    }

    private Object fieldValue(Object target, String fieldName) {
        if (target == null) return null;
        try {
            Field field = target.getClass().getField(fieldName);
            return field.get(target);
        } catch (Exception e) {
            return null;
        }
    }

    private Object outcomeValue(Object outcome) {
        if (outcome == null) return null;
        try {
            if (Boolean.FALSE.equals(invokeMethod(outcome, "isValid"))) {
                LOG.log(java.util.logging.Level.WARNING, "GreenHopper service returned an invalid result: " + outcome);
                return null;
            }
            return invokeMethod(outcome, "getValue");
        } catch (Exception e) {
            LOG.log(java.util.logging.Level.WARNING, "Could not read greenhopper service result", e);
            return null;
        }
    }

    /** Invokes a public method by name and argument types, across greenhopper's class loader. */
    private Object invokeMethod(Object target, String methodName, Object... args) throws ReflectiveOperationException {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != args.length) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            boolean compatible = true;
            for (int i = 0; i < args.length && compatible; i++) {
                compatible = parameterTypes[i].isPrimitive()
                    || args[i] == null
                    || parameterTypes[i].isInstance(args[i]);
            }
            if (compatible) {
                return method.invoke(target, args);
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + methodName + "/" + args.length);
    }

    private JsonObject fetchSprintReportOverHttp(long rapidViewId, long sprintId, HttpServletRequest request) {
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
            LOG.log(java.util.logging.Level.WARNING, "fetchSprintReportOverHttp failed", e);
            return null;
        }
    }

    /**
     * Drops expired entries once the cache grows past its bound. Entries are also discarded
     * when they are read, so this only keeps the footprint of a long running instance small.
     */
    private void evictExpiredReports() {
        if (REPORT_CACHE.size() <= REPORT_CACHE_MAX_ENTRIES) {
            return;
        }
        for (Map.Entry<String, CachedSprintReport> entry : REPORT_CACHE.entrySet()) {
            if (entry.getValue().isExpired()) {
                REPORT_CACHE.remove(entry.getKey(), entry.getValue());
            }
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

    /**
     * Story points for every issue the sprint report knows about, keyed by issue key. An issue
     * that was added after the sprint started and then removed is no longer part of the sprint,
     * so its points are only available here.
     */
    private Map<String, Double> reportEstimates(JsonObject contents) {
        Map<String, Double> estimates = new HashMap<>();
        for (String issueList : SPRINT_REPORT_ISSUE_LISTS) {
            JsonElement list = contents.get(issueList);
            if (list == null || !list.isJsonArray()) continue;
            for (JsonElement element : list.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                JsonObject issue = element.getAsJsonObject();
                if (!issue.has("key") || issue.get("key").isJsonNull()) continue;
                double points = 0;
                if (issue.has("estimateStatistic") && issue.get("estimateStatistic").isJsonObject()) {
                    points = sumEstimate(issue.getAsJsonObject("estimateStatistic").get("statFieldValue"));
                }
                estimates.put(issue.get("key").getAsString(), points);
            }
        }
        return estimates;
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