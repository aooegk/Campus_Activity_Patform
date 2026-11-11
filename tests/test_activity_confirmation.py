# -*- coding: utf-8 -*-
from uuid import uuid4

from test_api import DEADLINE, END, START, detail, login, req


def main():
    org, member = map(login, ("organizer", "demo01"))
    fields = {
        "title": "活动确认验收",
        "category": "学习组",
        "location": "教室",
        "conditions": "请自带纸笔并提前阅读讲义",
        "capacity": 1,
        "deadline": DEADLINE,
        "start_time": START,
        "end_time": END,
    }
    aid = req("POST", "/activities", token=org, body=fields)["id"]
    path = f"/activities/{aid}"
    cancel = {
        "version": 0,
        "confirmed": True,
        "reason": "活动场地临时关闭，需要取消本次活动",
        "expected_affected": 0,
    }
    assert req("POST", path + "/cancel", token=org)["ok"] is False
    assert req("POST", path + "/cancel", token=member, body=cancel)["ok"] is False
    assert req("POST", path + "/signup", token=member)["ok"] is False
    assert detail(org, aid)["formal"] == 0

    updated = req("PUT", path, token=org, body={
        **fields, "version": 0, "reason": "组织者更新了活动参与条件说明", "conditions": "请携带电脑",
    })
    assert updated["version"] == 1
    signup = {"conditions_confirmed": True, "version": 0}
    assert req("POST", path + "/signup", token=member, body=signup)["code"] == "ACTIVITY_VERSION_CONFLICT"
    assert req("POST", path + "/signup", token=member, body={**signup, "version": 1})["my_status"] == "FORMAL"
    assert req("POST", path + "/cancel", token=org, body=cancel)["code"] == "ACTIVITY_VERSION_CONFLICT"

    cancel["version"] = 1
    stale = req("POST", path + "/cancel", token=org, body=cancel)
    assert stale["code"] == "ACTIVITY_IMPACT_CHANGED" and stale["affected"] == 1
    assert detail(org, aid)["status"] == "OPEN"
    cancel["expected_affected"] = 1
    key = uuid4().hex
    result = req("POST", path + "/cancel", token=org, body=cancel, key=key)
    assert result["ok"] and result["affected"] == 1 and result["version"] == 2, result
    assert req("POST", path + "/cancel", token=org, body=cancel, key=key) == result
    assert detail(member, aid)["cancel_reason"] == cancel["reason"]
    notices = [n for n in req("GET", "/notifications", token=member)["list"]
               if n["activity_id"] == aid and n["type"] == "取消"]
    assert len(notices) == 1 and cancel["reason"] in notices[0]["content"], notices
    print("PASS - confirmed conditions and versioned cancellation impact and reasons")


if __name__ == "__main__":
    main()
