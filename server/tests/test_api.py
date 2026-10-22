import os
import tempfile
from pathlib import Path

# Must be configured before importing the app module.
tmp = tempfile.TemporaryDirectory()
os.environ["JWT_SECRET"] = "test-only-secret-please-never-use-in-production-12345"
os.environ["DATABASE_URL"] = f"sqlite:///{Path(tmp.name) / 'test.db'}"

from fastapi.testclient import TestClient
from app.main import app

client = TestClient(app)


def test_health():
    with client:
        assert client.get("/health").json()["status"] == "ok"


def test_registration_login_and_private_library():
    with client:
        a = {"email": "alice@example.org", "password": "alice-password-123"}
        b = {"email": "bob@example.org", "password": "bobs-password-123"}
        r = client.post("/v1/auth/register", json=a)
        assert r.status_code == 201
        token_a = r.json()["access_token"]
        assert client.post("/v1/auth/register", json=a).status_code == 409
        assert client.post("/v1/auth/login", json={**a, "password": "wrong-password-123"}).status_code == 401
        assert client.post("/v1/auth/login", json=a).status_code == 200
        token_b = client.post("/v1/auth/register", json=b).json()["access_token"]
        auth_a = {"Authorization": f"Bearer {token_a}"}
        auth_b = {"Authorization": f"Bearer {token_b}"}
        assert client.get("/v1/me").status_code in (401, 403)
        assert client.get("/v1/me", headers=auth_a).json()["email"] == a["email"]
        item = {"status": "watching", "episode": 4, "position_ms": 10000}
        assert client.put("/v1/library/101", json=item, headers=auth_a).status_code == 200
        assert client.get("/v1/library", headers=auth_b).json() == []
        assert client.get("/v1/library", headers=auth_a).json()[0]["episode"] == 4
        assert client.put("/v1/library/101", json={**item, "episode": -1}, headers=auth_a).status_code == 422
        assert client.delete("/v1/library/101", headers=auth_b).status_code == 204
        assert len(client.get("/v1/library", headers=auth_a).json()) == 1
        assert client.delete("/v1/library/101", headers=auth_a).status_code == 204
        assert client.get("/v1/library", headers=auth_a).json() == []
