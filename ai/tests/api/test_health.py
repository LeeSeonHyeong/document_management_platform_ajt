from fastapi.testclient import TestClient

from wiki_api.app import create_app


def test_health_is_public_and_returns_only_service_status():
    with TestClient(create_app(api_key="internal-secret")) as client:
        response = client.get("/health")

        assert response.status_code == 200
        assert response.json() == {"status": "UP"}
