#!/usr/bin/env node
// Runs the gadget's client-side JavaScript in a stubbed DOM/fetch sandbox and
// checks the behaviour that is hard to see in review: initial load, refresh
// timer, preference handling and the board selector.
//
// Usage: node tests/gadget-smoke-test.js

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const GADGET = path.join(
    __dirname, '..', 'my-jira-gadget', 'src', 'main', 'resources', 'gadget', 'my-gadget.xml');
const script = fs.readFileSync(GADGET, 'utf8').match(/<script>([\s\S]*?)<\/script>/)[1];

const SPRINT_DATA = {
    sprint: { id: 5, name: 'Sprint 1', state: 'ACTIVE' },
    health: {
        committedStoryPoints: 31, completedStoryPoints: 11, addedStoryPoints: 3,
        removedStoryPoints: 2, totalIssues: 9, completedIssues: 3,
        completionPercent: 35, issueCompletionPercent: 33
    },
    issuesByStatus: {
        'To Do': [{ key: 'SP-15', summary: 'Do the thing', issueType: 'Story', storyPoints: 2, statusCategory: 'new', assignee: 'a', priority: 'Medium' }],
        'Done': [{ key: 'SP-11', summary: 'Done thing', issueType: 'Story', storyPoints: 5, statusCategory: 'done', assignee: 'b', priority: 'High' }]
    }
};

const BOARDS = [
    { id: 5, name: 'Sprint Board' },
    { id: 6, name: 'SP Legacy (test)' }
];

const SPRINTS = {
    5: [{ id: 5, name: 'Sprint 1', state: 'ACTIVE', startDate: 1791763200000, endDate: 1792972800000 }],
    6: [{ id: 39, name: 'Sprint -1', state: 'CLOSED', startDate: 1672531200000, endDate: 1672617600000 }]
};

let failures = 0;
function assert(cond, msg) {
    if (!cond) {
        failures += 1;
        console.error('FAIL: ' + msg);
    } else {
        console.log('ok  - ' + msg);
    }
}
function section(name) {
    console.log('--- ' + name + ' ---');
}
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function El(id) {
    this.id = id;
    this.style = {};
    this.textContent = '';
    this.className = '';
    this.disabled = false;
    this.value = '';
    this.selected = false;
    this.attrs = {};
    this.handlers = {};
    this._children = [];
    this._html = '';
    Object.defineProperty(this, 'children', { get: () => this._children });
    Object.defineProperty(this, 'options', { get: () => this._children });
    Object.defineProperty(this, 'innerHTML', {
        get: () => this._html,
        set: (v) => { this._html = v; if (v === '') this._children = []; }
    });
    this.classList = {
        _c: new Set(),
        add(c) { this._c.add(c); },
        remove(c) { this._c.delete(c); },
        contains(c) { return this._c.has(c); }
    };
    this.addEventListener = (t, fn) => { this.handlers[t] = fn; };
    this.setAttribute = (k, v) => { this.attrs[k] = v; };
    this.getAttribute = (k) => (k in this.attrs ? this.attrs[k] : null);
    this.appendChild = (c) => { this._children.push(c); return c; };
    this.querySelector = () => null;
    this.querySelectorAll = () => [];
    this.removeChild = (c) => { this._children = this._children.filter(x => x !== c); };
}

function boot(options) {
    const opts = options || {};
    const prefs = Object.assign({
        sprintId: '5',
        boardId: '5',
        showHealth: 'true',
        showTable: 'true',
        groupByStatus: 'true',
        refreshInterval: '30'
    }, opts.prefs || {});
    const boards = opts.boards || BOARDS;
    const sprintsForBoard = opts.sprints || ((boardId) => SPRINTS[boardId] || []);
    const fetches = [];
    const intervals = [];
    const elements = {};
    const docHandlers = {};
    const winHandlers = {};

    const ok = (body) => Promise.resolve({
        ok: true,
        status: 200,
        text: () => Promise.resolve(JSON.stringify(body))
    });
    function fakeFetch(url) {
        fetches.push(url);
        if (url.indexOf('/boards') !== -1) {
            if (opts.failBoards) {
                return Promise.resolve({ ok: false, status: 500, text: () => Promise.resolve('boom') });
            }
            return ok({ boards });
        }
        if (url.indexOf('/sprints') !== -1) {
            const match = /boardId=(\d+)/.exec(url);
            return ok({ sprints: match ? sprintsForBoard(parseInt(match[1], 10)) : sprintsForBoard(5) });
        }
        return ok(opts.data || SPRINT_DATA);
    }

    const document = {
        hidden: false,
        getElementById(id) {
            if (!elements[id]) elements[id] = new El(id);
            return elements[id];
        },
        createElement(tag) { return new El(tag); },
        addEventListener(t, fn) { docHandlers[t] = fn; }
    };

    const sandbox = {
        document,
        fetch: fakeFetch,
        console,
        setTimeout, clearTimeout, setImmediate,
        setInterval(fn, delay) { intervals.push({ fn, delay, cleared: false }); return intervals.length; },
        clearInterval(id) { if (intervals[id - 1]) intervals[id - 1].cleared = true; },
        gadgets: {
            Prefs: function () {
                this.getString = (n) => prefs[n];
                this.set = (n, v) => { prefs[n] = v; };
            },
            window: { adjustHeight() {} }
        },
        location: { origin: 'http://localhost:2990', pathname: '/jira/secure/DashboardManager!default.jspa', reload() {} }
    };
    sandbox.window = sandbox;
    sandbox.globalThis = sandbox;
    sandbox.window.location = sandbox.location;
    sandbox.window.addEventListener = (t, fn) => { winHandlers[t] = fn; };
    sandbox.window.setInterval = sandbox.setInterval;
    sandbox.window.clearInterval = sandbox.clearInterval;
    sandbox.window.document = document;

    vm.createContext(sandbox);
    vm.runInContext(script, sandbox, { filename: 'my-gadget.js' });

    const activeIntervals = () => intervals.filter(i => !i.cleared);

    return {
        prefs,
        elements,
        document,
        sandbox,
        fetches,
        activeIntervals,
        dataCalls: () => fetches.filter(u => u.indexOf('/data?sprintId=') !== -1).length,
        tick() {
            const active = activeIntervals();
            if (!active.length) throw new Error('no active auto-refresh timer');
            active[0].fn();
        },
        fireMessage(newPrefs) {
            winHandlers['message']({ data: { gadget: { prefs: newPrefs } } });
        },
        fireDocument(name) {
            docHandlers[name]();
        },
        failFetch() {
            sandbox.fetch = () => Promise.resolve({ ok: false, status: 500, text: () => Promise.resolve('boom') });
        },
        restoreFetch() {
            sandbox.fetch = fakeFetch;
        }
    };
}

async function storedBoardScenario() {
    section('initial load (stored board pref)');
    const app = boot({});
    await sleep(100);
    assert(app.fetches[0].indexOf('/rest/sprint/1.0/boards') !== -1,
        'initial load fetched the board list first (got ' + app.fetches[0] + ')');
    assert(app.fetches.some(u => u.indexOf('/sprints?boardId=5') !== -1), 'sprint list loaded for the stored board');
    assert(app.dataCalls() === 1, 'initial load fetched sprint data once (got ' + app.dataCalls() + ')');
    assert(app.elements.healthGrid.children.length === 8,
        'health grid rendered 8 cards (got ' + app.elements.healthGrid.children.length + ')');
    assert(/^Updated /.test(app.elements.lastUpdated.textContent),
        'last-updated label populated: "' + app.elements.lastUpdated.textContent + '"');
    assert(app.elements.progressPercent.textContent.indexOf('35%') === 0,
        'progress label shows 35%: ' + app.elements.progressPercent.textContent);
    assert(app.elements.issueCount.textContent === '2 issues',
        'issue count rendered: ' + app.elements.issueCount.textContent);
    assert(app.activeIntervals().length === 1, 'exactly one auto-refresh timer registered');
    assert(app.activeIntervals()[0].delay === 30000,
        'timer delay is 30000ms (got ' + app.activeIntervals()[0].delay + ')');
    assert(app.sandbox.window.__sprintPulseRefreshTimer !== null, 'timer id stored on window');

    section('auto refresh tick');
    const before = app.dataCalls();
    app.tick();
    await sleep(60);
    assert(app.dataCalls() === before + 1, 'tick triggered a fresh data fetch (got ' + app.dataCalls() + ')');
    assert(app.activeIntervals().length === 1, 'no duplicate timer after tick');
    assert(app.elements.lastUpdated.textContent.indexOf('Updated ') === 0, 'last-updated label still populated');

    section('hidden tab');
    app.document.hidden = true;
    const hiddenBefore = app.dataCalls();
    app.tick();
    await sleep(60);
    assert(app.dataCalls() === hiddenBefore, 'tick skipped while tab hidden');
    app.document.hidden = false;

    section('visibilitychange (fresh data)');
    const visBefore = app.dataCalls();
    app.fireDocument('visibilitychange');
    await sleep(60);
    assert(app.dataCalls() === visBefore, 'visibilitychange did not refresh fresh data (no thrash)');

    section('pref change to Off');
    app.fireMessage({ refreshInterval: '0' });
    assert(app.activeIntervals().length === 0, 'timer cleared when auto refresh set to Off');
    assert(app.sandbox.window.__sprintPulseRefreshTimer === null, 'window timer id nulled');

    section('pref change to 60s');
    app.fireMessage({ refreshInterval: '60' });
    assert(app.activeIntervals().length === 1 && app.activeIntervals()[0].delay === 60000,
        'timer restarted with 60s delay');

    section('error path stops timer');
    app.failFetch();
    app.tick();
    await sleep(60);
    assert(app.activeIntervals().length === 0, 'timer stopped after a failed refresh');
    app.restoreFetch();

    section('in-gadget interval control');
    const sel = app.elements.refreshIntervalSelect;
    assert(!!sel, 'interval select exists in the gadget body');
    assert(sel.value === '60', 'select reflects the stored pref (got "' + sel.value + '")');
    sel.value = '0';
    sel.handlers['change'].call(sel);
    assert(app.prefs.refreshInterval === '0', 'preference persisted: ' + app.prefs.refreshInterval);
    assert(app.activeIntervals().length === 0, 'selecting off stops the timer');
    sel.value = '30';
    sel.handlers['change'].call(sel);
    assert(app.prefs.refreshInterval === '30', 'preference persisted: ' + app.prefs.refreshInterval);
    assert(app.activeIntervals().length === 1 && app.activeIntervals()[0].delay === 30000,
        'selecting 30s restarts the timer');

    section('unsupported stored value falls back');
    app.prefs.refreshInterval = '45';
    app.fireMessage({ refreshInterval: '45' });
    assert(app.prefs.refreshInterval === '45', 'unsupported value not persisted');
    assert(app.elements.refreshIntervalSelect.value === '30',
        'unsupported value falls back to 30s in the select (got "' + app.elements.refreshIntervalSelect.value + '")');
    assert(app.activeIntervals()[0].delay === 30000, 'timer runs at the fallback interval');

    section('board selector');
    const boardSel = app.elements.boardSelect;
    assert(!!boardSel, 'board select exists in the gadget body');
    assert(boardSel.children.length === 3,
        'board select lists all boards plus the all-boards option (got ' + boardSel.children.length + ')');
    assert(boardSel.children[0].value === '' && boardSel.children[0].textContent === 'All boards',
        'first option is All boards');
    assert(boardSel.value === '5', 'board select reflects the stored board pref (got "' + boardSel.value + '")');
    assert(boardSel.style.display !== 'none', 'board select is visible when boards are available');

    boardSel.value = '6';
    boardSel.handlers['change'].call(boardSel);
    await sleep(60);
    assert(app.prefs.boardId === '6', 'board preference persisted after change: ' + app.prefs.boardId);
    assert(app.fetches.some(u => u.indexOf('/sprints?boardId=6') !== -1), 'sprint list reloaded for the selected board');
    assert(app.prefs.sprintId === '39', 'first sprint of the new board selected and persisted (got ' + app.prefs.sprintId + ')');
    assert(boardSel.value === '6', 'board select keeps the chosen board');
}

async function staleBoardScenario() {
    section('stale board pref falls back to all boards');
    const app = boot({ prefs: { boardId: '99', sprintId: '5' } });
    await sleep(100);
    assert(app.prefs.boardId === '', 'stale board preference cleared');
    assert(app.elements.boardSelect.value === '', 'select shows All boards');
    assert(!app.fetches.some(u => u.indexOf('boardId=99') !== -1), 'stale board was not requested');
    assert(app.fetches.some(u => u.indexOf('/sprints') !== -1 && u.indexOf('boardId=') === -1),
        'sprint list loaded for all boards');
    assert(app.dataCalls() === 1, 'sprint data still loaded (got ' + app.dataCalls() + ')');
}

async function singleBoardScenario() {
    section('single board hides the selector');
    const app = boot({ boards: [BOARDS[0]] });
    await sleep(100);
    assert(app.elements.boardSelect.style.display === 'none', 'board select hidden when only one board exists');
    assert(app.elements.boardSelect.children.length === 2,
        'select still lists All boards and the board (got ' + app.elements.boardSelect.children.length + ')');
    assert(app.dataCalls() === 1, 'single-board data loaded (got ' + app.dataCalls() + ')');
}

async function boardsUnavailableScenario() {
    section('board list unavailable keeps the sprint list working');
    const app = boot({ failBoards: true });
    await sleep(100);
    assert(app.elements.boardSelect.style.display === 'none', 'board select hidden when the board list fails');
    assert(app.dataCalls() === 1, 'sprint list and data still load (got ' + app.dataCalls() + ')');
}

async function escapingScenario() {
    section('Jira data is escaped before rendering');
    const payload = '<img src=x onerror="alert(1)">';
    const app = boot({
        data: Object.assign({}, SPRINT_DATA, {
            issuesByStatus: {
                ['<b>Status</b>']: [{
                    key: 'SP-1"><script>', summary: payload, issueType: '<i>Story</i>', storyPoints: 1,
                    statusCategory: 'new', assignee: '<u>Eve</u> "Q"', priority: '<s>High</s>',
                    blocked: true, blockedReason: 'Blocked by SP-2: "' + payload + '"'
                }]
            }
        })
    });
    await sleep(100);
    const html = app.elements.issuesBody.innerHTML;
    assert(html.length > 0, 'issue table rendered');
    assert(html.indexOf('<img') === -1 && html.indexOf('<script>') === -1 && html.indexOf('<b>') === -1
        && html.indexOf('<i>') === -1 && html.indexOf('<u>') === -1 && html.indexOf('<s>') === -1,
        'no Jira-supplied markup reaches the table');
    assert(html.indexOf('&lt;img src=x onerror=&quot;alert(1)&quot;&gt;') !== -1, 'summary shown as text');
    assert(html.indexOf('/browse/SP-1%22%3E%3Cscript%3E"') !== -1,
        'issue key encoded in the link');

    app.sandbox.fetch = () => Promise.resolve({ ok: false, status: 500, text: () => Promise.resolve('<img src=x>') });
    app.elements.refreshBtn.handlers['click']();
    await sleep(60);
    const errorHtml = app.elements.gadget.innerHTML;
    assert(errorHtml.indexOf('<img') === -1 && errorHtml.indexOf('&lt;img src=x&gt;') !== -1,
        'error text from the server is escaped');
}

(async () => {
    await storedBoardScenario();
    await staleBoardScenario();
    await singleBoardScenario();
    await boardsUnavailableScenario();
    await escapingScenario();
    console.log(failures ? 'SMOKE TEST: ' + failures + ' FAILURE(S)' : 'SMOKE TEST: ALL PASSED');
    process.exitCode = failures ? 1 : 0;
})();
