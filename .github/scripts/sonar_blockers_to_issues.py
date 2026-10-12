#!/usr/bin/env python3
"""Sync SonarQube Cloud BLOCKER issues into GitHub issues, one GitHub issue per Sonar rule.

Every GitHub issue carries a hidden marker with its rule key, so reruns update the same issue:
the location list is rewritten, a rule without blockers is closed, and a rule that comes back
reopens its issue. Standard library only, so it runs on a bare ubuntu-latest runner.

Env: GITHUB_TOKEN, GITHUB_REPOSITORY (owner/repo), SONAR_ORG, SONAR_PROJECT,
SONAR_BRANCH (default main), SYNC_LABEL, GIT_REF (commit for source links, default branch).
"""
import json
import os
import urllib.error
import urllib.parse
import urllib.request

SONAR = "https://sonarcloud.io"
GITHUB = "https://api.github.com"
PAGE_SIZE = 500
MARKER = "<!-- sonar-rule:{} -->"
LABEL_COLOR = "4e9bcd"

TOKEN = os.environ["GITHUB_TOKEN"]
REPO = os.environ["GITHUB_REPOSITORY"]
ORG = os.environ["SONAR_ORG"]
PROJECT = os.environ["SONAR_PROJECT"]
BRANCH = os.environ.get("SONAR_BRANCH", "main")
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


def fetch_blockers():
    issues, page = [], 1
    while True:
        result = sonar("/api/issues/search", componentKeys=PROJECT, branch=BRANCH,
                       severities="BLOCKER", issueStatuses="OPEN,CONFIRMED",
                       ps=PAGE_SIZE, p=page)
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


def render(rule, name, issues):
    rule_url = f"{SONAR}/organizations/{ORG}/rules?open={rule}&rule_key={rule}"
    lines = [
        MARKER.format(rule),
        f"**SonarQube Cloud BLOCKER**: [`{rule}`]({rule_url}) {name}",
        "",
        f"{len(issues)} open melding(en) op `{BRANCH}`:",
        "",
    ]
    for issue in sorted(issues, key=lambda i: (i["component"], i.get("line", 0))):
        path = issue["component"].split(":", 1)[1]
        line = issue.get("line")
        anchor = f"#L{line}" if line else ""
        where = f"{path}:{line}" if line else path
        sonar_url = f"{SONAR}/project/issues?id={PROJECT}&open={issue['key']}"
        lines.append(f"- [`{where}`](https://github.com/{REPO}/blob/{GIT_REF}/{path}{anchor})"
                     f": {issue['message']} ([Sonar]({sonar_url}))")
    lines += [
        "",
        "_Automatisch bijgewerkt door `.github/workflows/sonarqube.yml` na elke scan op "
        f"`{BRANCH}`; handmatige wijzigingen in deze tekst worden overschreven. Los de code op "
        "(of markeer de melding in SonarQube Cloud) en de volgende scan werkt dit issue bij "
        "of sluit het._",
    ]
    return "\n".join(lines)


def main():
    ensure_label()
    by_rule = {}
    for issue in fetch_blockers():
        by_rule.setdefault(issue["rule"], []).append(issue)
    synced = existing_sync_issues()

    for rule, issues in sorted(by_rule.items()):
        name = sonar("/api/rules/show", key=rule, organization=ORG)["rule"]["name"]
        title = f"SonarQube blocker: {name} ({len(issues)}x)"
        body = render(rule, name, issues)
        current = synced.get(rule)
        if current is None:
            created = github("POST", "/issues", {"title": title, "body": body, "labels": [LABEL]})
            print(f"created #{created['number']} {rule} ({len(issues)})")
        elif current["state"] == "closed" or current["title"] != title or current["body"] != body:
            github("PATCH", f"/issues/{current['number']}",
                   {"title": title, "body": body, "state": "open"})
            print(f"updated #{current['number']} {rule} ({len(issues)})")
        else:
            print(f"unchanged #{current['number']} {rule}")

    for rule, issue in synced.items():
        if rule not in by_rule and issue["state"] == "open":
            github("POST", f"/issues/{issue['number']}/comments",
                   {"body": f"Geen BLOCKER-meldingen meer voor `{rule}` in de laatste scan op "
                            f"`{BRANCH}`. Automatisch gesloten."})
            github("PATCH", f"/issues/{issue['number']}", {"state": "closed"})
            print(f"closed #{issue['number']} {rule}")


if __name__ == "__main__":
    main()
