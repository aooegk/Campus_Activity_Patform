# -*- coding: utf-8 -*-
from datetime import datetime, timedelta
from time import sleep

from test_api import detail, login, req


def main():
    org, first, exiting, no_show, outsider = map(login, ("organizer", "demo01", "demo02", "demo03", "demo04"))
    now = datetime.fromisoformat(req("GET", "/health")["server_time"]).replace(microsecond=0)
    fields = {
        "title": "活动签到验收",
        "category": "学习组",
        "location": "教室",
        "capacity": 4,
        "deadline": (now + timedelta(seconds=8)).isoformat(),
        "start_time": (now + timedelta(seconds=10)).isoformat(),
        "end_time": (now + timedelta(seconds=15)).isoformat(),
    }
    aid = req("POST", "/activities", token=org, body=fields)["id"]
    path = f"/activities/{aid}"
    for token in (first, exiting, no_show, org):
        assert req("POST", path + "/signup", token=token)["my_status"] == "FORMAL"

    attendance = path + "/attendance"
    assert req("POST", attendance + "/code", token=first)["ok"] is False
    code = req("POST", attendance + "/code", token=org)
    assert len(code["code"]) == 6 and code["code"].isdigit()
    next_code = req("POST", attendance + "/code", token=org)
    if next_code["expires_at"] == code["expires_at"]:
        assert next_code == code
    code = next_code
    code_expiry = datetime.fromisoformat(code["expires_at"])
    seconds_left = (code_expiry - datetime.fromisoformat(req("GET", "/health")["server_time"])).total_seconds()
    if seconds_left < 2:
        sleep(max(0, seconds_left + 0.2))
        code = req("POST", attendance + "/code", token=org)
    assert datetime.fromisoformat(code["expires_at"]) <= datetime.fromisoformat(fields["end_time"])
    assert req("POST", attendance + "/check-in", token=outsider, body={"code": code["code"]})["ok"] is False

    checkin = {"code": code["code"]}
    first_sign = req("POST", attendance + "/check-in", token=first, body=checkin)
    assert first_sign["ok"], first_sign
    repeat = req("POST", attendance + "/check-in", token=first, body=checkin)
    assert repeat == first_sign, (first_sign, repeat)
    assert req("POST", attendance + "/check-in", token=exiting, body=checkin)["ok"]
    pid = detail(exiting, aid)["participation_id"]
    assert req("POST", f"/participations/{pid}/withdraw", token=exiting)["my_status"] == "WITHDRAWN"

    wrong_code = "000000" if code["code"] != "000000" else "111111"
    for _ in range(5):
        response = req("POST", attendance + "/check-in", token=no_show, body={"code": wrong_code})
        assert response["code"] == "CHECKIN_WRONG_CODE", response
    assert req("POST", attendance + "/check-in", token=no_show, body=checkin)["code"] == "CHECKIN_RATE_LIMIT"

    end = datetime.fromisoformat(fields["end_time"])
    remaining = (end - datetime.fromisoformat(req("GET", "/health")["server_time"])).total_seconds()
    sleep(max(0, remaining + 0.2))
    review = req("GET", attendance, token=org)
    assert review["review_open"] and not review["check_in_open"]
    assert len(review["rows"]) == 3 and sum(bool(row["checked_in_at"]) for row in review["rows"]) == 1, review
    assert req("GET", attendance, token=exiting)["eligible"] is False
    first_id = req("GET", "/me", token=first)["user"]["id"]
    self_id = req("GET", "/me", token=org)["user"]["id"]
    review_path = f"{attendance}/{first_id}"
    assert req("PUT", review_path, token=org, body={"version": 0, "status": "ABSENT"})["ok"] is False
    assert req("PUT", f"{attendance}/{self_id}", token=org, body={
        "version": 0, "status": "PRESENT", "reason": "组织者提交签到缺失补正申请",
    })["ok"] is False
    confirmed = req("PUT", review_path, token=org, body={"version": 0, "status": "PRESENT"})
    assert confirmed == {"ok": True, "version": 1, "review_status": "PRESENT"}, confirmed
    assert req("PUT", review_path, token=org, body={"version": 0, "status": "PRESENT"})["ok"] is False
    assert req("GET", attendance, token=first)["own"]["review_status"] == "PRESENT"
    assert any(n["activity_id"] == aid and "出勤结果" in n["content"] for n in req("GET", "/notifications", token=first)["list"])
    feedback = path + "/feedback"
    assert req("GET", feedback)["total"] == 0
    assert req("PUT", feedback, token=no_show, body={"score": 5})["ok"] is False
    assert req("PUT", feedback, token=first, body={"score": 6})["ok"] is False
    assert req("PUT", feedback, token=first, body={"score": 5, "content": "活动组织有序"})["ok"]
    assert req("PUT", feedback, token=first, body={"score": 4, "content": "场地可以再宽敞一些"})["ok"]
    summary = req("GET", feedback)
    assert summary["total"] == 1 and summary["average"] == 4, summary
    assert "entries" not in summary and "own" not in summary
    assert req("GET", feedback, token=first)["own"]["content"] == "场地可以再宽敞一些"
    assert req("GET", feedback, token=org)["entries"][0]["content"] == "场地可以再宽敞一些"
    assert "entries" not in req("GET", feedback, token=outsider)
    print("PASS - check-in window, code access, rate limit, start snapshot, versioned reviews and private feedback")


if __name__ == "__main__":
    main()
