# -*- coding: utf-8 -*-
"""在启用演示数据的独立数据库上验证请求回执。"""
from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4

from test_api import DEADLINE, END, START, authed_reset, detail, login, req


def main():
    authed_reset()
    organizer, member = map(login, ("organizer", "demo01"))
    key = uuid4().hex
    fields = {
        "title": "请求回执验收 " + key[:8],
        "category": "学习组",
        "location": "教室",
        "capacity": 2,
        "deadline": DEADLINE,
        "start_time": START,
        "end_time": END,
    }

    def publish(_):
        return req("POST", "/activities", token=organizer, body=fields, key=key)

    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(publish, range(8)))
    activity = results[0]["id"]
    assert all(result == {"ok": True, "id": activity} for result in results), results
    assert [a["id"] for a in req("GET", "/activities", token=organizer)
            if a["title"] == fields["title"]] == [activity]
    assert req("POST", "/activities", token=organizer, body=dict(reversed(list(fields.items()))), key=key) == results[0]
    conflict = req("POST", "/activities", token=organizer, body={**fields, "capacity": 3}, key=key)
    assert conflict["code"] == "IDEMPOTENCY_KEY_CONFLICT", conflict
    conflict = req("POST", f"/activities/{activity}/signup", token=organizer, key=key)
    assert conflict["code"] == "IDEMPOTENCY_KEY_CONFLICT", conflict

    signup = req("POST", f"/activities/{activity}/signup", token=member, key=key)
    assert signup["my_status"] == "FORMAL", signup
    assert req("POST", f"/participations/{signup['participation_id']}/withdraw", token=member,
               key=uuid4().hex)["my_status"] == "WITHDRAWN"
    assert req("POST", f"/activities/{activity}/signup", token=member, key=key) == signup
    assert detail(member, activity)["my_status"] == "WITHDRAWN"

    invalid_key = uuid4().hex
    invalid = req("POST", "/activities", token=organizer, body={**fields, "capacity": 0}, key=invalid_key)
    assert invalid["ok"] is False, invalid
    assert req("POST", "/activities", token=organizer, body=fields, key=invalid_key)["ok"] is True
    print("PASS - concurrent writes, request fingerprints, account scope, persisted replay and rollback")


if __name__ == "__main__":
    main()
