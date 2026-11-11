# -*- coding: utf-8 -*-
import http.cookiejar
import json
import urllib.error
import urllib.request
from uuid import uuid4

from test_api import BASE


def request(path, *, method="GET", body=None, headers=None, opener=None):
    req = urllib.request.Request(
        BASE + path, method=method, headers={"Content-Type": "application/json", **(headers or {})},
        data=json.dumps(body).encode() if body is not None else None,
    )
    try:
        response = (opener or urllib.request.build_opener()).open(req, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.code, json.load(response), response.headers


def main():
    account = "session-" + uuid4().hex[:12]
    credentials = {"account": account, "password": "SessionTest123"}
    assert request("/register", method="POST", body=credentials)[0] == 200

    cookies = http.cookiejar.CookieJar()
    browser = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
    status, login, headers = request("/login", method="POST", body=credentials, opener=browser)
    assert status == 200 and login["ok"], login
    assert any("campus_session=" in cookie and "HttpOnly" in cookie and "SameSite=Lax" in cookie
               for cookie in headers.get_all("Set-Cookie")), headers
    csrf = next(cookie.value for cookie in cookies if cookie.name == "campus_csrf")
    assert request("/me", opener=browser)[1]["user"]["account"] == account
    assert request("/notifications/read-all", method="POST", opener=browser)[0] == 403
    assert request("/notifications/read-all", method="POST", headers={"X-CSRF-Token": csrf}, opener=browser)[0] == 200
    assert request("/logout", method="POST", headers={"X-CSRF-Token": csrf}, opener=browser)[0] == 200
    assert request("/me", opener=browser)[0] == 401
    assert request("/me", headers={"Authorization": "Bearer " + login["token"]})[0] == 401

    tokens = [request("/login", method="POST", body=credentials)[1]["token"] for _ in range(2)]
    assert request("/me/password", method="POST", headers={"Authorization": "Bearer " + tokens[0]},
                   body={"old_password": credentials["password"], "new_password": "ChangedSession123"})[0] == 200
    assert all(request("/me", headers={"Authorization": "Bearer " + token})[0] == 401 for token in tokens)
    new_credentials = {**credentials, "password": "ChangedSession123"}
    incorrect = {**credentials, "password": "InvalidPassword1"}
    for _ in range(4):
        assert request("/login", method="POST", body=incorrect)[0] == 401
    assert request("/login", method="POST", body=new_credentials)[0] == 200
    for _ in range(5):
        assert request("/login", method="POST", body=incorrect)[0] == 401
    assert request("/login", method="POST", body=new_credentials)[0] == 401
    print("PASS - session cookies, CSRF, logout, password revocation and five-attempt account lock")


if __name__ == "__main__":
    main()
