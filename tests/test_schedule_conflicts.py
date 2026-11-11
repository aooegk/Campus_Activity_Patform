# -*- coding: utf-8 -*-
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta

from test_api import DEADLINE, END, START, authed_reset, detail, login, req


def main():
    authed_reset()
    org, first, second, reserve = map(login, ("organizer", "demo01", "demo02", "demo03"))
    fields = {
        "title": "日程重叠验收",
        "category": "学习组",
        "location": "教室",
        "capacity": 1,
        "deadline": DEADLINE,
        "start_time": START,
        "end_time": END,
    }
    a, b = [req("POST", "/activities", token=org, body=fields)["id"] for _ in range(2)]

    def signup(aid, token, confirmed=None):
        body = {"confirmed_conflicts": confirmed} if confirmed is not None else None
        return req("POST", f"/activities/{aid}/signup", token=token, body=body)

    with ThreadPoolExecutor(max_workers=2) as pool:
        responses = list(pool.map(lambda aid: signup(aid, first), (a, b)))
    success = [r for r in responses if r.get("my_status") == "FORMAL"]
    conflicts = [r for r in responses if r.get("code") == "SCHEDULE_CONFLICT"]
    assert len(success) == len(conflicts) == 1, responses
    prior = success[0]["activity_id"]
    assert [item["id"] for item in conflicts[0]["conflicts"]] == [prior]
    target = b if prior == a else a
    assert signup(target, first, [target])["code"] == "SCHEDULE_CONFLICT"
    assert detail(first, target)["formal"] == 0
    assert signup(target, first, [prior])["my_status"] == "FORMAL"

    touching = req("POST", "/activities", token=org, body={
        **fields, "start_time": END, "end_time": (datetime.fromisoformat(END) + timedelta(hours=1)).isoformat(),
    })["id"]
    assert signup(touching, first)["my_status"] == "FORMAL"
    amend = {**fields, "version": 0, "reason": "组织者准备把活动调整到另一场活动时间"}
    update = req("PUT", f"/activities/{touching}", token=org, body=amend)
    assert update["code"] == "RESCHEDULE_TIME_CONFLICT" and update["affected"] == 1, update
    assert req("PUT", f"/activities/{touching}", token=org,
               body={**amend, "confirmed_conflict_count": 1})["schedule_conflicts"] == 1

    candidate = req("POST", "/activities", token=org, body=fields)["id"]
    p = signup(candidate, reserve)
    assert signup(candidate, second)["my_status"] == "WAITING"
    assert signup(prior, second)["my_status"] == "WAITING"
    other = req("POST", "/activities", token=org, body=fields)["id"]
    assert signup(other, second)["my_status"] == "FORMAL"
    req("POST", f"/participations/{p['participation_id']}/withdraw", token=reserve)
    invitation = detail(second, candidate)["invitation_id"]
    accept = f"/invitations/{invitation}/accept"
    warning = req("POST", accept, token=second)
    assert warning["code"] == "SCHEDULE_CONFLICT" and [r["id"] for r in warning["conflicts"]] == [other]
    assert detail(second, candidate)["my_status"] == "INVITED"
    assert req("POST", accept, token=second, body={"confirmed_conflicts": [other]})["my_status"] == "FORMAL"
    print("PASS - concurrent schedule checks, half-open times, reschedule impact and invitation confirmation")


if __name__ == "__main__":
    main()
