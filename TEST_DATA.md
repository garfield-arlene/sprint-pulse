# Sprint Pulse Test Data Setup

## Important
- Always run from: `/home/michael.devicariis/work/jira/my-jira-gadget`
- Always use `JAVA_HOME=/usr/lib/jvm/java-11-openjdk`
- Jira base URL: http://localhost:2990/jira
- Login: admin/admin
- Plugin version: 3.1.0
- Story Points custom field: customfield_10007 (as referenced in code)

## H2 Database Rules
- NEVER include `.mv.db` extension in JDBC URL. H2 appends it automatically.
- Bad: `jdbc:h2:file:./target/jira/home/database/h2db.mv.db` → creates `h2db.mv.db.mv.db`
- Good: `jdbc:h2:file:./target/jira/home/database/h2db`

## Working DB
The test DB that has been working is the clean test resources DB copied to the Jira home.

From: `/home/michael.devicariis/work/jira/my-jira-gadget/target/jira/tmp-resources/jira-plugin-test-resources-9.4.0/jira-home/database/h2db.mv.db`
To: `/home/michael.devicariis/work/jira/my-jira-gadget/target/jira/home/database/h2db.mv.db`

## Manual Data Insert (what worked)
After copying clean DB:

```sql
INSERT INTO "PUBLIC"."PROJECT" (ID, pkey, pname, url, lead, description, assigneetype, avatar, originalkey, projecttype) VALUES (10000, 'SP', 'Sprint Pulse Project', '', 'admin', 'Test project for Sprint Pulse gadget', 0, 10001, 'SP', 'software');
INSERT INTO "PUBLIC"."AO_60DB71_RAPIDVIEW" (CARD_COLOR_STRATEGY, ID, KAN_PLAN_ENABLED, NAME, OLD_DONE_ISSUES_CUTOFF, OWNER_USER_NAME, REFINED_VELOCITY_ACTIVE, SAVED_FILTER_ID, SHOW_DAYS_IN_COLUMN, SHOW_EPIC_AS_PANEL, SPRINTS_ENABLED, SWIMLANE_STRATEGY) VALUES ('none', 1, 0, 'SP Board', '0', 'admin', 0, 0, 0, 0, 1, 'parentchild');
INSERT INTO "PUBLIC"."BOARDPROJECT" (BOARD_ID, PROJECT_ID) VALUES (1, 10000);
INSERT INTO "PUBLIC"."BOARD" (ID, JQL) VALUES (1, 'project = SP ORDER BY Rank ASC');
INSERT INTO "PUBLIC"."AO_60DB71_SPRINT" (ACTIVATED_DATE, AUTO_START_STOP, CLOSED, COMPLETE_DATE, END_DATE, GOAL, ID, NAME, RAPID_VIEW_ID, SEQUENCE, STARTED, START_DATE, SYNCED) VALUES (1630000000000, 1, 0, NULL, 1630100000000, 'Sprint goal', 1, 'Sprint 1', 1, 0, 1, 1630000000000, 0);
```

## Quick Restore
```bash
cd /home/michael.devicariis/work/jira/my-jira-gadget
killall -9 java 2>&1; sleep 15
rm -f target/jira/home/database/h2db.* target/jira/home/.jira-home.lock
cp target/jira/tmp-resources/jira-plugin-test-resources-9.4.0/jira-home/database/h2db.mv.db target/jira/home/database/h2db.mv.db
# Insert data with RunScript using file: URL (no extension)
java -cp ~/.m2/repository/com/h2database/h2/1.4.200/h2-1.4.200.jar org.h2.tools.RunScript -url "jdbc:h2:file:./target/jira/home/database/h2db" -user sa -script /tmp/insert2.sql
java -cp ~/.m2/repository/com/h2database/h2/1.4.200/h2-1.4.200.jar org.h2.tools.RunScript -url "jdbc:h2:file:./target/jira/home/database/h2db" -user sa -script /tmp/insert4.sql
java -cp ~/.m2/repository/com/h2database/h2/1.4.200/h2-1.4.200.jar org.h2.tools.RunScript -url "jdbc:h2:file:./target/jira/home/database/h2db" -user sa -script /tmp/insert6.sql
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk
setsid /tmp/opencode/start-jira.sh < /dev/null > /dev/null 2>&1 &
```
