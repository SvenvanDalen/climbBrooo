#!/usr/bin/env python3
"""Sync open SonarQube Cloud issues into GitHub issues, one GitHub issue per Sonar rule.

Every GitHub issue carries a hidden marker with its rule key, so reruns update the same issue:
the location list is rewritten, a rule without open findings is closed, and a rule that comes
back reopens its issue. Standard library only, so it runs on a bare ubuntu-latest runner.

Env: GITHUB_TOKEN, GITHUB_REPOSITORY (owner/repo), SONAR_ORG, SONAR_PROJECT,
SONAR_BRANCH (default main), SONAR_SEVERITIES (comma list, empty = all), SYNC_LABEL,
GIT_REF (commit for source links, default branch).
"""
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request

SONAR = "https://sonarcloud.io"
GITHUB = "https://api.github.com"
PAGE_SIZE = 500
MARKER = "<!-- sonar-rule:{} -->"
LABEL_COLOR = "4e9bcd"
SEVERITY_ORDER = ["BLOCKER", "CRITICAL", "MAJOR", "MINOR", "INFO"]
# GitHub caps an issue body at 65 536 characters; leave room for header and footer.
BODY_BUDGET = 60_000
# Stay well under GitHub's secondary rate limit for content creation.
CREATE_PAUSE_S = 2

TOKEN = os.environ["GITHUB_TOKEN"]
REPO = os.environ["GITHUB_REPOSITORY"]
ORG = os.environ["SONAR_ORG"]
PROJECT = os.environ["SONAR_PROJECT"]
BRANCH = os.environ.get("SONAR_BRANCH", "main")
SEVERITIES = os.environ.get("SONAR_SEVERITIES", "")
LABEL = os.environ.get("SYNC_LABEL", "sonarqube analysis")
GIT_REF = os.environ.get("GIT_REF") or BRANCH


def request(method, url, body=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    with urllib.request.urlopen(req) as resp:
        raw = resp.read()
    return json.loads(raw) if raw else None


def sonar(path, **params):
    return request("GET", f"{SONAR}{path}?{urllib.parse.urlencode(params)}")


def github(method, path, body=None):
    headers = {
        "Authorization": f"Bearer {TOKEN}",
        "Accept": "application/vnd.github+json",
        "Content-Type": "application/json",
    }
    return request(method, f"{GITHUB}/repos/{REPO}{path}", body, headers)


def fetch_open_issues():
    params = {"componentKeys": PROJECT, "branch": BRANCH, "issueStatuses": "OPEN,CONFIRMED",
              "ps": PAGE_SIZE}
    if SEVERITIES:
        params["severities"] = SEVERITIES
    issues, page = [], 1
    while True:
        result = sonar("/api/issues/search", p=page, **params)
        issues += result["issues"]
        if page * PAGE_SIZE >= result["paging"]["total"]:
            return issues
        page += 1


def existing_sync_issues():
    """Marker rule key -> GitHub issue, open and closed, pull requests skipped."""
    found, page = {}, 1
    label = urllib.parse.quote(LABEL)
    while True:
        batch = github("GET", f"/issues?labels={label}&state=all&per_page=100&page={page}")
        for issue in batch:
            body = issue.get("body") or ""
            if "pull_request" in issue or "<!-- sonar-rule:" not in body:
                continue
            rule = body.split("<!-- sonar-rule:", 1)[1].split(" -->", 1)[0]
            found[rule] = issue
        if len(batch) < 100:
            return found
        page += 1


def ensure_label():
    try:
        github("GET", f"/labels/{urllib.parse.quote(LABEL)}")
    except urllib.error.HTTPError as e:
        if e.code != 404:
            raise
        github("POST", "/labels", {"name": LABEL, "color": LABEL_COLOR,
                                   "description": "Gevonden door SonarQube Cloud"})


def severity_rank(severity):
    return SEVERITY_ORDER.index(severity) if severity in SEVERITY_ORDER else len(SEVERITY_ORDER)


def worst_severity(issues):
    return min((i.get("severity", "INFO") for i in issues), key=severity_rank)


def render(rule, name, severity, kind, issues):
    rule_url = f"{SONAR}/organizations/{ORG}/rules?open={rule}&rule_key={rule}"
    all_url = (f"{SONAR}/project/issues?id={PROJECT}&branch={BRANCH}&rules={rule}"
               "&issueStatuses=OPEN,CONFIRMED")
    head = [
        MARKER.format(rule),
        f"**SonarQube Cloud {severity} {kind}**: [`{rule}`]({rule_url}) {name}",
        "",
        f"{len(issues)} open melding(en) op `{BRANCH}` "
        f"([alle meldingen in SonarQube Cloud]({all_url})):",
        "",
    ]
    footer = [
        "",
        "_Automatisch bijgewerkt door `.github/workflows/sonarqube.yml` na elke scan op "
        f"`{BRANCH}`; handmatige wijzigingen in deze tekst worden overschreven. Los de code op "
        "(of markeer de melding in SonarQube Cloud) en de volgende scan werkt dit issue bij "
        "of sluit het._",
    ]
    lines, used = [], sum(len(s) + 1 for s in head + footer)
    ordered = sorted(issues, key=lambda i: (i["component"], i.get("line", 0)))
    for n, issue in enumerate(ordered):
        path = issue["component"].split(":", 1)[1]
        line = issue.get("line")
        where = f"{path.rsplit('/', 1)[-1]}:{line}" if line else path.rsplit("/", 1)[-1]
        anchor = f"#L{line}" if line else ""
        entry = (f"- [`{where}`](https://github.com/{REPO}/blob/{GIT_REF}/{path}{anchor})"
                 f": {issue['message']}")
        if used + len(entry) + 100 > BODY_BUDGET:
            lines.append(f"- ... en nog {len(ordered) - n} meer, zie SonarQube Cloud.")
            break
        lines.append(entry)
        used += len(entry) + 1
    return "\n".join(head + lines + footer)


def main():
    ensure_label()
    by_rule = {}
    for issue in fetch_open_issues():
        by_rule.setdefault(issue["rule"], []).append(issue)
    synced = existing_sync_issues()

    ordered_rules = sorted(by_rule, key=lambda r: (severity_rank(worst_severity(by_rule[r])),
                                                   -len(by_rule[r]), r))
    for rule in ordered_rules:
        issues = by_rule[rule]
        severity = worst_severity(issues)
        meta = sonar("/api/rules/show", key=rule, organization=ORG)["rule"]
        kind = issues[0].get("type", meta.get("type", "")).replace("_", " ").lower()
        title = f"SonarQube {severity.lower()}: {meta['name']} ({len(issues)}x)"
        body = render(rule, meta["name"], severity, kind, issues)
        current = synced.get(rule)
        if current is None:
            created = github("POST", "/issues", {"title": title, "body": body, "labels": [LABEL]})
            print(f"created #{created['number']} {rule} ({len(issues)})")
            time.sleep(CREATE_PAUSE_S)
        elif current["state"] == "closed" or current["title"] != title or current["body"] != body:
            github("PATCH", f"/issues/{current['number']}",
                   {"title": title, "body": body, "state": "open"})
            print(f"updated #{current['number']} {rule} ({len(issues)})")
        else:
            print(f"unchanged #{current['number']} {rule}")

    for rule, issue in synced.items():
        if rule not in by_rule and issue["state"] == "open":
            github("POST", f"/issues/{issue['number']}/comments",
                   {"body": f"Geen open meldingen meer voor `{rule}` in de laatste scan op "
                            f"`{BRANCH}`. Automatisch gesloten."})
            github("PATCH", f"/issues/{issue['number']}", {"state": "closed"})
            print(f"closed #{issue['number']} {rule}")


if __name__ == "__main__":
    main()
