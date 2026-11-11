# -*- coding: utf-8 -*-
"""在隔离演示库上运行，设置 SWEEP_SECONDS=2 及全天递补。"""
from datetime import datetime, timedelta
from time import monotonic, sleep

from test_api import DEADLINE, END, START, detail, login, req


def main():
    org, first, waiting, outsider = map(login, ("organizer", "demo01", "demo02", "demo03"))
    fields = {
        "title": "个人活动与通知验收",
        "category": "学习组",
        "location": "教室",
        "capacity": 1,
        "deadline": DEADLINE,
        "start_time": START,
        "end_time": END,
    }
    aid = req("POST", "/activities", token=org, body=fields)["id"]
    path = f"/activities/{aid}"
    formal = req("POST", path + "/signup", token=first)
    queued = req("POST", path + "/signup", token=waiting)
    assert formal["my_status"] == "FORMAL" and queued["my_rank"] == 1
    pid = queued["participation_id"]
    mine = next(p for p in req("GET", "/my", token=waiting) if p["activity_id"] == aid)
    assert mine["my_view"] == "waiting" and mine["my_rank"] == 1, mine

    req("POST", f"/participations/{formal['participation_id']}/withdraw", token=first)
    invitation = detail(waiting, aid)["invitation_id"]
    page = req("GET", "/notifications?page=1&size=1", token=waiting)
    note = page["list"][0]
    assert note["invitation_id"] == invitation and note["invitation_status"] == "PENDING", note
    assert page["total"] >= 2 and page["size"] == 1
    assert req("GET", "/notifications?page=2&size=1", token=waiting)["list"][0]["id"] != note["id"]
    read_path = f"/notifications/{note['id']}/read"
    assert req("POST", read_path, token=outsider)["ok"] is False
    assert req("POST", read_path, token=waiting)["ok"] is True
    assert req("POST", read_path, token=waiting)["ok"] is True
    assert req("GET", "/notifications", token=waiting)["unread"] == page["unread"] - 1
    assert detail(waiting, aid)["my_status"] == "INVITED"
    assert req("POST", f"/invitations/{invitation}/accept", token=waiting)["my_status"] == "FORMAL"
    trail = f"/my/participations/{pid}/history"
    assert req("GET", trail, token=outsider)["ok"] is False
    assert [e["change_kind"] for e in req("GET", trail, token=waiting)] == [
        "INVITATION_ACCEPTED", "INVITATION_ISSUED", "SIGNUP",
    ]

    assert req("POST", path + "/cancel", token=org, body={
        "version": 0, "confirmed": True, "reason": "活动场地临时关闭，需要取消本次活动",
    })["ok"]
    assert detail(outsider, aid)["ok"] is False
    assert detail(waiting, aid)["my_status"] == "CANCELLED"
    assert req("GET", trail, token=waiting)[0]["change_kind"] == "ACTIVITY_CANCELLED"

    now = datetime.fromisoformat(req("GET", "/health")["server_time"])
    closed = req("POST", "/activities", token=org, body={
        **fields, "deadline": (now + timedelta(seconds=5)).isoformat(timespec="seconds"),
    })["id"]
    path = f"/activities/{closed}"
    req("POST", path + "/signup", token=first)
    pid = req("POST", path + "/signup", token=waiting)["participation_id"]
    until = monotonic() + 10
    while monotonic() < until:
        mine = next(p for p in req("GET", "/my", token=waiting) if p["activity_id"] == closed)
        if mine["status"] == "EXPIRED":
            break
        sleep(0.3)
    assert mine["status"] == "EXPIRED" and mine["my_view"] == "history" and mine["my_rank"] is None, mine
    assert detail(org, closed)["waiting"] == 0
    assert req("GET", f"/my/participations/{pid}/history", token=waiting)[0]["change_kind"] == "REGISTRATION_CLOSED"
    print("PASS - participation events, private archive, cutoff settlement and notification read state")


if __name__ == "__main__":
    main()
