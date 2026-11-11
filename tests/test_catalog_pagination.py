# -*- coding: utf-8 -*-
from urllib.parse import urlencode
from uuid import uuid4

from test_api import DEADLINE, END, START, authed_reset, detail, login, req


def main():
    authed_reset()
    org, a, b, c, d = map(login, ("organizer", "demo01", "demo02", "demo03", "demo04"))
    tag = "catalog" + uuid4().hex[:8]
    fields = {
        "title": "分页与名单验收",
        "category": "学习组",
        "description": tag,
        "location": "望江教室",
        "capacity": 2,
        "deadline": DEADLINE,
        "start_time": START,
        "end_time": END,
    }
    ids = [req("POST", "/activities", token=org, body={**fields, "title": fields["title"] + str(i)})["id"]
           for i in range(3)]
    query = urlencode({"q": tag.upper(), "category": "学习组", "campus": "望江", "period": "晚上", "size": 2})
    page1 = req("GET", "/activities?" + query + "&page=1")
    page2 = req("GET", "/activities?" + query + "&page=2")
    assert page1["total"] == page2["total"] == 3
    assert [row["id"] for row in page1["items"] + page2["items"]] == list(reversed(ids))
    assert req("GET", "/activities?" + urlencode({"q": tag + "%"})) == []
    assert req("GET", "/activities?page=1&size=51")["ok"] is False
    assert isinstance(req("GET", "/activities"), list)

    organized = req("GET", "/my/organized?page=1&size=2", token=org)
    assert [row["id"] for row in organized["items"]] == ids[:0:-1], organized
    path = f"/activities/{ids[-1]}"
    for user, expected in [(a, "FORMAL"), (b, "FORMAL"), (c, "WAITING"), (d, "WAITING")]:
        assert req("POST", path + "/signup", token=user)["my_status"] == expected
    org_detail = req("GET", path + "?include_roster=false", token=org)
    assert org_detail["is_organizer"] and "roster" not in org_detail
    assert len(detail(org, ids[-1])["roster"]) == 4
    roster = path + "/roster?"
    waiting1 = req("GET", roster + "status=WAITING&page=1&size=1", token=org)
    waiting2 = req("GET", roster + "status=WAITING&page=2&size=1", token=org)
    assert waiting1["total"] == 2 and waiting1["items"][0]["id"] < waiting2["items"][0]["id"]
    assert req("GET", roster + "status=FORMAL", token=a)["ok"] is False
    participation = detail(a, ids[-1])["participation_id"]
    req("POST", f"/participations/{participation}/withdraw", token=a)
    pending = req("GET", roster + "status=INVITED", token=org)
    assert pending["total"] == 1 and pending["items"][0]["expires_at"]
    assert req("GET", roster + "status=WAITING", token=org)["total"] == 1
    print("PASS - filtered and stable catalogue pages, owner-only roster and pending invites")


if __name__ == "__main__":
    main()
