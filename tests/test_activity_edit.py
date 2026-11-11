# -*- coding: utf-8 -*-
"""在独立演示库上运行。设置 DEMO_ENABLED=true、INVITE_TTL 至少 10 秒 和全天递补。"""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta

from test_api import DEADLINE, END, START, authed_reset, detail, login, req


def main():
    authed_reset()
    org, a, b, c = map(login, ("organizer", "demo01", "demo02", "demo03"))
    activity = {
        "title": "活动编辑验收",
        "category": "学习组",
        "description": "编辑前",
        "conditions": "",
        "location": "旧地点",
        "capacity": 2,
        "deadline": DEADLINE,
        "start_time": START,
        "end_time": END,
    }
    aid = req("POST", "/activities", token=org, body=activity)["id"]
    path = f"/activities/{aid}"
    assert detail(org, aid)["version"] == 0
    for user, expected in [(a, "FORMAL"), (b, "FORMAL"), (c, "WAITING")]:
        assert req("POST", path + "/signup", token=user)["my_status"] == expected
    command = {
        **activity,
        "version": 0,
        "reason": "根据到场同学人数调整教室和活动名额",
        "location": "新地点",
    }
    assert req("PUT", path, token=a, body=command)["ok"] is False
    assert req("PUT", path, token=org, body={**command, "capacity": 1})["ok"] is False
    assert req("PUT", path, token=org, body={**command, "capacity": 2.9})["ok"] is False
    assert req("PUT", path, token=org, body={**command, "reason": "太短"})["ok"] is False
    assert detail(org, aid)["version"] == 0

    updated = {**command, "capacity": 3}
    with ThreadPoolExecutor(max_workers=2) as pool:
        responses = list(pool.map(lambda _: req("PUT", path, token=org, body=updated), range(2)))
    accepted = [r for r in responses if r.get("ok")]
    assert len(accepted) == 1, responses
    assert accepted[0]["version"] == 1 and accepted[0]["affected"] == 3, accepted
    assert accepted[0]["changes"]["location"] == {"before": "旧地点", "after": "新地点"}
    assert any(r.get("code") == "ACTIVITY_VERSION_CONFLICT" for r in responses), responses

    status = detail(c, aid)
    assert status["location"] == "新地点" and status["capacity"] == 3, status
    assert status["my_status"] == "INVITED" and status["reserved"] == 1, status
    original_expiry = status["invitation_expires_at"]
    retry = {**updated, "version": 1}
    assert req("PUT", path, token=org, body={**retry, "capacity": 2})["ok"] is False
    early = (datetime.fromisoformat(original_expiry) - timedelta(seconds=1)).isoformat(timespec="seconds")
    assert req("PUT", path, token=org, body={**retry, "deadline": early})["ok"] is False
    assert detail(c, aid)["invitation_expires_at"] == original_expiry

    unchanged = req("PUT", path, token=org, body=retry)
    assert unchanged["version"] == 1 and unchanged["changes"] == {} and unchanged["affected"] == 0
    notifications = req("GET", "/notifications", token=c)["list"]
    changes = [n for n in notifications if n["activity_id"] == aid and n["type"] == "变更"]
    assert len(changes) == 1 and updated["reason"] in changes[0]["content"], changes

    # 旧改期接口也递增版本，防止旧客户端改期后被新编辑表单覆盖。
    new_end = (datetime.fromisoformat(END) + timedelta(hours=1)).isoformat()
    legacy = req("POST", path + "/reschedule", token=org, body={
        "deadline": DEADLINE, "start_time": START, "end_time": new_end,
    })
    assert legacy["version"] == 2, legacy
    assert req("PUT", path, token=org, body=retry)["code"] == "ACTIVITY_VERSION_CONFLICT"
    print("PASS - edit validation, concurrent version check, promotion, notifications and legacy reschedule")


if __name__ == "__main__":
    main()
